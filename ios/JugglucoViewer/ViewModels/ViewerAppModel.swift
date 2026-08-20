import Foundation
import Combine

enum ViewerContentSource: Equatable {
    case none
    case live(receivedAt: Date)
    case cache(savedAt: Date)
}

enum ViewerConfigurationError: LocalizedError, Equatable {
    case tokenMissing
    case tokenTooShort

    var errorDescription: String? {
        switch self {
        case .tokenMissing: return "Введите отдельный ключ только для просмотра."
        case .tokenTooShort: return "Новый ключ просмотра должен содержать не меньше 32 символов."
        }
    }
}

@MainActor
final class ViewerAppModel: ObservableObject {
    typealias APIClientFactory = @Sendable (ViewerConfiguration) -> any ViewerAPIClientProtocol

    @Published private(set) var snapshot: ViewerSnapshot?
    @Published private(set) var health: HealthResponse?
    @Published private(set) var contentSource: ViewerContentSource = .none
    @Published private(set) var isRefreshing = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var cacheWarning: String?
    @Published private(set) var hasStoredToken = false
    @Published private(set) var serverURLText: String

    private let configurationStore: ConfigurationStore
    private let tokenStore: any TokenStoring
    private let cache: any SnapshotCaching
    private let apiFactory: APIClientFactory
    private let now: @Sendable () -> Date
    private var pollingTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var refreshTaskGeneration: Int?
    private var refreshTaskID: UInt64?
    private var nextRefreshTaskID: UInt64 = 0
    private var configurationGeneration = 0
    private var didLoadInitialState = false

    init(
        configurationStore: ConfigurationStore = ConfigurationStore(),
        tokenStore: any TokenStoring = KeychainTokenStore(),
        cache: any SnapshotCaching = FileSnapshotCache(),
        apiFactory: @escaping APIClientFactory = { configuration in
            ViewerAPIClient(baseURL: configuration.baseURL, token: configuration.token)
        },
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.configurationStore = configurationStore
        self.tokenStore = tokenStore
        self.cache = cache
        self.apiFactory = apiFactory
        self.now = now
        self.serverURLText = configurationStore.loadBaseURLText()
        self.hasStoredToken = (try? tokenStore.readToken()) != nil
    }

    deinit {
        pollingTask?.cancel()
        refreshTask?.cancel()
    }

    var isConfigured: Bool {
        !serverURLText.isEmpty && hasStoredToken
    }

    var currentReadingIsStale: Bool {
        guard let snapshot, let reading = snapshot.newestReading else { return true }
        return reading.isReadingStale(relativeToServerTimeMs: effectiveServerNowMs(for: snapshot))
    }

    var currentReadingAge: TimeInterval? {
        guard let snapshot, let reading = snapshot.newestReading else { return nil }
        return reading.age(relativeToServerTimeMs: effectiveServerNowMs(for: snapshot))
    }

    var historyIsIncomplete: Bool {
        snapshot?.glucoseHistoryTruncated == true || snapshot?.intakeEventsTruncated == true
    }

    func forecastIsCurrent(in snapshot: ViewerSnapshot) -> Bool {
        guard snapshot.forecast.canDisplayTrajectory,
              let anchorMs = snapshot.forecast.basedOnReadingAtMs,
              let finalPointMs = snapshot.forecast.points.last?.atMs else { return false }
        let effectiveNowMs = effectiveServerNowMs(for: snapshot)
        let anchorAgeMs = effectiveNowMs - anchorMs
        return (0 ... 15 * 60 * 1_000).contains(anchorAgeMs) &&
            finalPointMs >= anchorMs && effectiveNowMs <= finalPointMs
    }

    func beginForegroundUpdates() {
        pollingTask?.cancel()
        pollingTask = Task { [weak self] in
            guard let self else { return }
            if self.didLoadInitialState {
                await self.refresh()
            } else {
                await self.loadInitialStateIfNeeded()
            }
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(60))
                } catch {
                    return
                }
                await self.refresh()
            }
        }
    }

    func endForegroundUpdates() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    func loadInitialStateIfNeeded() async {
        guard !didLoadInitialState else { return }
        didLoadInitialState = true
        guard let configuration = try? configuration() else {
            contentSource = .none
            return
        }
        let generation = configurationGeneration
        do {
            if let cached = try await cache.load(scopeID: configuration.cacheScopeID) {
                let activeScopeID = try? self.configuration().cacheScopeID
                guard generation == configurationGeneration,
                      activeScopeID == configuration.cacheScopeID else { return }
                snapshot = cached.snapshot.normalized
                contentSource = .cache(savedAt: cached.savedAt)
            }
        } catch {
            cacheWarning = "Защищённый офлайн-кэш недоступен."
        }
        await refresh()
    }

    func refresh() async {
        guard let configuration = try? configuration() else {
            contentSource = .none
            errorMessage = nil
            return
        }
        let generation = configurationGeneration
        if let activeTask = refreshTask, refreshTaskGeneration == generation {
            await activeTask.value
            return
        }
        nextRefreshTaskID &+= 1
        let taskID = nextRefreshTaskID
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.performRefresh(
                configuration: configuration,
                generation: generation,
                taskID: taskID
            )
        }
        refreshTask = task
        refreshTaskGeneration = generation
        refreshTaskID = taskID
        isRefreshing = true
        errorMessage = nil
        await task.value
    }

    private func performRefresh(
        configuration: ViewerConfiguration,
        generation: Int,
        taskID: UInt64
    ) async {
        defer {
            if refreshTaskID == taskID {
                refreshTask = nil
                refreshTaskGeneration = nil
                refreshTaskID = nil
                isRefreshing = false
            }
        }
        let client = apiFactory(configuration)
        do {
            let fresh = try await client.fetchSnapshot()
            guard generation == configurationGeneration, !Task.isCancelled else { return }
            let freshHealth = try? await client.fetchHealth()
            guard generation == configurationGeneration, !Task.isCancelled else { return }
            let receivedAt = now()
            snapshot = fresh
            contentSource = .live(receivedAt: receivedAt)
            health = freshHealth
            do {
                try await cache.save(
                    fresh,
                    savedAt: receivedAt,
                    scopeID: configuration.cacheScopeID
                )
                guard generation == configurationGeneration, !Task.isCancelled else { return }
                cacheWarning = nil
            } catch {
                guard generation == configurationGeneration, !Task.isCancelled else { return }
                cacheWarning = "Онлайн-данные получены, но защищённый офлайн-кэш недоступен."
            }
        } catch {
            guard generation == configurationGeneration, !Task.isCancelled else { return }
            health = try? await client.fetchHealth()
            guard generation == configurationGeneration, !Task.isCancelled else { return }
            errorMessage = Self.userFacingMessage(for: error)
            if snapshot == nil {
                contentSource = .none
            } else if case .live(let receivedAt) = contentSource {
                // A failed refresh makes even previously live content explicitly cached/offline.
                contentSource = .cache(savedAt: receivedAt)
            }
        }
    }

    func saveConfiguration(baseURLText: String, newToken: String) async throws {
        let url = try ServerURLValidator.validate(baseURLText)
        let trimmedToken = newToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedToken.isEmpty && trimmedToken.count < 32 {
            throw ViewerConfigurationError.tokenTooShort
        }
        let existingToken = try tokenStore.readToken()
        let previousURL = try? ServerURLValidator.validate(serverURLText)
        let endpointChanged = previousURL != url
        let token: String?
        if trimmedToken.isEmpty {
            token = endpointChanged ? nil : existingToken
        } else {
            token = trimmedToken
        }
        guard let token, !token.isEmpty else { throw ViewerConfigurationError.tokenMissing }

        let previousConfiguration = try? configuration()
        let newConfiguration = ViewerConfiguration(baseURL: url, token: token)
        if previousConfiguration?.cacheScopeID != newConfiguration.cacheScopeID {
            await invalidateInFlightRefresh()
            try await cache.clear()
            snapshot = nil
            health = nil
            contentSource = .none
        }
        if !trimmedToken.isEmpty {
            try tokenStore.saveToken(trimmedToken)
        }
        configurationStore.saveBaseURL(url)
        serverURLText = url.absoluteString
        hasStoredToken = true
        didLoadInitialState = true
        await refresh()
    }

    func disconnect() async throws {
        endForegroundUpdates()
        await invalidateInFlightRefresh()
        var cleanupError: Error?
        do {
            try tokenStore.deleteToken()
        } catch {
            cleanupError = error
        }
        do {
            try await cache.clear()
        } catch {
            if cleanupError == nil { cleanupError = error }
        }
        configurationStore.clearBaseURL()
        serverURLText = ""
        hasStoredToken = false
        snapshot = nil
        health = nil
        contentSource = .none
        errorMessage = nil
        cacheWarning = nil
        didLoadInitialState = false
        if let cleanupError { throw cleanupError }
    }

    private func invalidateInFlightRefresh() async {
        configurationGeneration &+= 1
        let task = refreshTask
        refreshTask = nil
        refreshTaskGeneration = nil
        refreshTaskID = nil
        isRefreshing = false
        task?.cancel()
        if let task { await task.value }
    }

    private func configuration() throws -> ViewerConfiguration {
        let url = try ServerURLValidator.validate(serverURLText)
        guard let token = try tokenStore.readToken(), !token.isEmpty else {
            throw ViewerConfigurationError.tokenMissing
        }
        return ViewerConfiguration(baseURL: url, token: token)
    }

    func effectiveServerNowMs(for snapshot: ViewerSnapshot) -> Int64 {
        let reference: Date
        switch contentSource {
        case .live(let receivedAt): reference = receivedAt
        case .cache(let savedAt): reference = savedAt
        case .none: reference = now()
        }
        let elapsed = now().timeIntervalSince(reference)
        if elapsed < -5 {
            // A backwards wall-clock jump makes elapsed freshness unknowable.
            // Fail closed: age the reading past 15 minutes and the forecast past
            // its last point instead of freezing old medical data as "fresh".
            let readingExpiredAt = (snapshot.newestReading?.measuredAtMs ?? snapshot.serverTimeMs) +
                15 * 60 * 1_000 + 1
            let forecastExpiredAt = (snapshot.forecast.points.last?.atMs ?? snapshot.serverTimeMs) + 1
            return max(snapshot.serverTimeMs, max(readingExpiredAt, forecastExpiredAt))
        }
        let elapsedMs = Int64(max(0, elapsed) * 1_000)
        return snapshot.serverTimeMs + elapsedMs
    }

    private static func userFacingMessage(for error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return "Нет подключения к интернету. Показана последняя сохранённая копия."
            case .timedOut: return "Сервер не ответил вовремя. Показана последняя сохранённая копия."
            default: return "Не удалось связаться с сервером. Показана последняя сохранённая копия."
            }
        }
        if let localized = error as? LocalizedError,
           let description = localized.errorDescription {
            return description
        }
        return "Не удалось обновить данные. Показана последняя сохранённая копия."
    }
}
