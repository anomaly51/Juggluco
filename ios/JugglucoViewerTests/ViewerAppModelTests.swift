import Foundation
import XCTest
@testable import JugglucoViewer

@MainActor
final class ViewerAppModelTests: XCTestCase {
    func testOfflineAgeAdvancesAndExpiredForecastIsHidden() async throws {
        let clock = MutableClock(Date(timeIntervalSince1970: 1_000))
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, _) = makeModel(clock: clock, api: api)

        await model.loadInitialStateIfNeeded()
        XCTAssertFalse(model.currentReadingIsStale)
        XCTAssertTrue(try model.forecastIsCurrent(in: XCTUnwrap(model.snapshot)))

        clock.advance(by: 11 * 60)

        XCTAssertTrue(model.currentReadingIsStale, "Five-minute-old data must become stale after eleven offline minutes")
        XCTAssertFalse(try model.forecastIsCurrent(in: XCTUnwrap(model.snapshot)))
        XCTAssertEqual(try XCTUnwrap(model.currentReadingAge), 16 * 60, accuracy: 0.1)
    }

    func testFailedRefreshPreservesLastSuccessfulCacheTimestamp() async throws {
        let successfulAt = Date(timeIntervalSince1970: 2_000)
        let clock = MutableClock(successfulAt)
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, _) = makeModel(clock: clock, api: api)
        await model.loadInitialStateIfNeeded()
        XCTAssertEqual(model.contentSource, .live(receivedAt: successfulAt))

        clock.advance(by: 7 * 60)
        await api.failFutureSnapshots()
        await model.refresh()

        XCTAssertEqual(model.contentSource, .cache(savedAt: successfulAt))
        XCTAssertNotNil(model.errorMessage)
    }

    func testBackwardDeviceClockFailsClosed() async throws {
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, _) = makeModel(clock: clock, api: api)
        await model.loadInitialStateIfNeeded()
        XCTAssertFalse(model.currentReadingIsStale)

        clock.advance(by: -60 * 60)

        XCTAssertTrue(model.currentReadingIsStale)
        XCTAssertFalse(try model.forecastIsCurrent(in: XCTUnwrap(model.snapshot)))
    }

    func testCacheIsScopedAndClearedWhenTokenChanges() async throws {
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, cache) = makeModel(clock: clock, api: api)
        await model.loadInitialStateIfNeeded()
        let initialSaveCount = await cache.saveCount
        XCTAssertEqual(initialSaveCount, 1)

        try await model.saveConfiguration(
            baseURLText: "https://viewer.example",
            newToken: "different-viewer-token-000000000000"
        )

        let clearCount = await cache.clearCount
        XCTAssertEqual(clearCount, 1)
    }

    func testChangingEndpointRequiresFreshToken() async throws {
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, _) = makeModel(clock: clock, api: api)

        do {
            try await model.saveConfiguration(
                baseURLText: "https://different.example",
                newToken: ""
            )
            XCTFail("Expected a new token to be required")
        } catch {
            XCTAssertTrue(error is ViewerConfigurationError)
        }
        XCTAssertEqual(model.serverURLText, "https://viewer.example")
    }

    func testNewShortTokenIsRejectedLocally() async throws {
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let api = SequencedViewerAPI(snapshot: try TestFixtures.snapshot())
        let (model, _) = makeModel(clock: clock, api: api)

        do {
            try await model.saveConfiguration(
                baseURLText: "https://viewer.example",
                newToken: "too-short"
            )
            XCTFail("Expected short token rejection")
        } catch {
            XCTAssertEqual(error as? ViewerConfigurationError, .tokenTooShort)
        }
    }

    func testOldProfileCannotPublishAfterConfigurationSwitch() async throws {
        let snapshotA = try TestFixtures.snapshot()
        let snapshotB = try snapshotWithCurrentGlucose(144)
        let apiA = SuspendedViewerAPI()
        let apiB = SequencedViewerAPI(snapshot: snapshotB)
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let suite = UserDefaults(suiteName: "ViewerRace-\(UUID().uuidString)")!
        let store = ConfigurationStore(defaults: suite)
        store.saveBaseURL(URL(string: "https://a.example")!)
        let tokens = MemoryTokenStore(token: "token-a")
        let cache = MemorySnapshotCache()
        let model = ViewerAppModel(
            configurationStore: store,
            tokenStore: tokens,
            cache: cache,
            apiFactory: { configuration in
                if configuration.baseURL.host == "a.example" { return apiA }
                return apiB
            },
            now: { clock.value }
        )

        let oldRefresh = Task { await model.refresh() }
        await apiA.waitUntilStarted()
        let switchTask = Task {
            try await model.saveConfiguration(
                baseURLText: "https://b.example",
                newToken: "token-b-0000000000000000000000000"
            )
        }
        await Task.yield()
        await apiA.resume(with: snapshotA)
        try await switchTask.value
        await oldRefresh.value

        XCTAssertEqual(model.snapshot?.currentGlucose?.glucoseMgDl, 144)
        XCTAssertEqual(model.serverURLText, "https://b.example")
        let switchedSaveCount = await cache.saveCount
        XCTAssertEqual(switchedSaveCount, 1, "Only profile B may reach the cache")
    }

    func testOldResponseCannotRepopulateAfterDisconnect() async throws {
        let snapshotA = try TestFixtures.snapshot()
        let apiA = SuspendedViewerAPI()
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let suite = UserDefaults(suiteName: "ViewerDisconnect-\(UUID().uuidString)")!
        let store = ConfigurationStore(defaults: suite)
        store.saveBaseURL(URL(string: "https://a.example")!)
        let tokens = MemoryTokenStore(token: "token-a")
        let cache = MemorySnapshotCache()
        let model = ViewerAppModel(
            configurationStore: store,
            tokenStore: tokens,
            cache: cache,
            apiFactory: { _ in apiA },
            now: { clock.value }
        )

        let oldRefresh = Task { await model.refresh() }
        await apiA.waitUntilStarted()
        let disconnectTask = Task { try await model.disconnect() }
        await Task.yield()
        await apiA.resume(with: snapshotA)
        try await disconnectTask.value
        await oldRefresh.value

        XCTAssertNil(model.snapshot)
        XCTAssertFalse(model.hasStoredToken)
        XCTAssertEqual(model.serverURLText, "")
        let disconnectedSaveCount = await cache.saveCount
        XCTAssertEqual(disconnectedSaveCount, 0)
    }

    func testDelayedCacheCannotRepopulateAfterDisconnect() async throws {
        let snapshot = try TestFixtures.snapshot()
        let clock = MutableClock(Date(timeIntervalSince1970: 2_000))
        let suite = UserDefaults(suiteName: "ViewerCacheRace-\(UUID().uuidString)")!
        let store = ConfigurationStore(defaults: suite)
        let baseURL = URL(string: "https://a.example")!
        store.saveBaseURL(baseURL)
        let token = "token-a"
        let tokens = MemoryTokenStore(token: token)
        let configuration = ViewerConfiguration(baseURL: baseURL, token: token)
        let cached = CachedSnapshot(
            scopeID: configuration.cacheScopeID,
            savedAt: clock.value,
            snapshot: snapshot
        )
        let cache = DelayedLoadCache(value: cached)
        let api = SequencedViewerAPI(snapshot: snapshot)
        let model = ViewerAppModel(
            configurationStore: store,
            tokenStore: tokens,
            cache: cache,
            apiFactory: { _ in api },
            now: { clock.value }
        )

        let loadTask = Task { await model.loadInitialStateIfNeeded() }
        await cache.waitUntilStarted()
        try await model.disconnect()
        await cache.resumeLoad()
        await loadTask.value

        XCTAssertNil(model.snapshot)
        XCTAssertEqual(model.contentSource, .none)
    }

    func testDisconnectClearsVisibleStateEvenWhenCleanupFails() async throws {
        let suite = UserDefaults(suiteName: "ViewerCleanup-\(UUID().uuidString)")!
        let store = ConfigurationStore(defaults: suite)
        store.saveBaseURL(URL(string: "https://a.example")!)
        let snapshot = try TestFixtures.snapshot()
        let api = SequencedViewerAPI(snapshot: snapshot)
        let model = ViewerAppModel(
            configurationStore: store,
            tokenStore: FailingDeleteTokenStore(),
            cache: FailingClearCache(),
            apiFactory: { _ in api }
        )
        await model.refresh()
        XCTAssertNotNil(model.snapshot)

        do {
            try await model.disconnect()
            XCTFail("Expected cleanup error")
        } catch {}

        XCTAssertNil(model.snapshot)
        XCTAssertNil(model.health)
        XCTAssertEqual(model.contentSource, .none)
        XCTAssertEqual(model.serverURLText, "")
        XCTAssertFalse(model.hasStoredToken)
    }

    private func makeModel(
        clock: MutableClock,
        api: SequencedViewerAPI
    ) -> (ViewerAppModel, MemorySnapshotCache) {
        let suiteName = "ViewerAppModelTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        let configurationStore = ConfigurationStore(defaults: defaults)
        configurationStore.saveBaseURL(URL(string: "https://viewer.example")!)
        let tokens = MemoryTokenStore(token: "viewer-token")
        let cache = MemorySnapshotCache()
        let model = ViewerAppModel(
            configurationStore: configurationStore,
            tokenStore: tokens,
            cache: cache,
            apiFactory: { _ in api },
            now: { clock.value }
        )
        return (model, cache)
    }
}

private func snapshotWithCurrentGlucose(_ value: Double) throws -> ViewerSnapshot {
    var object = try XCTUnwrap(JSONSerialization.jsonObject(with: TestFixtures.snapshotJSON) as? [String: Any])
    var current = try XCTUnwrap(object["current_glucose"] as? [String: Any])
    current["glucose_mg_dl"] = value
    object["current_glucose"] = current
    let data = try JSONSerialization.data(withJSONObject: object)
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(ViewerSnapshot.self, from: data).normalized
}

private final class MutableClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ date: Date) { current = date }

    var value: Date {
        lock.withLock { current }
    }

    func advance(by interval: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(interval) }
    }
}

private final class MemoryTokenStore: TokenStoring {
    private var token: String?
    init(token: String?) { self.token = token }
    func readToken() throws -> String? { token }
    func saveToken(_ token: String) throws { self.token = token }
    func deleteToken() throws { token = nil }
}

private actor MemorySnapshotCache: SnapshotCaching {
    private var cached: CachedSnapshot?
    private(set) var saveCount = 0
    private(set) var clearCount = 0

    func load(scopeID: String) -> CachedSnapshot? {
        cached?.scopeID == scopeID ? cached : nil
    }

    func save(_ snapshot: ViewerSnapshot, savedAt: Date, scopeID: String) {
        cached = CachedSnapshot(scopeID: scopeID, savedAt: savedAt, snapshot: snapshot)
        saveCount += 1
    }

    func clear() {
        cached = nil
        clearCount += 1
    }
}

private actor SuspendedViewerAPI: ViewerAPIClientProtocol {
    private var continuation: CheckedContinuation<ViewerSnapshot, Error>?
    private var started = false

    func waitUntilStarted() async {
        while !started { await Task.yield() }
    }

    func resume(with snapshot: ViewerSnapshot) {
        continuation?.resume(returning: snapshot)
        continuation = nil
    }

    func fetchHealth() async throws -> HealthResponse {
        XCTFail("A stale profile must not continue to health after invalidation")
        throw URLError(.cancelled)
    }

    func fetchSnapshot() async throws -> ViewerSnapshot {
        started = true
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
        }
    }
}

private actor DelayedLoadCache: SnapshotCaching {
    private let value: CachedSnapshot
    private var continuation: CheckedContinuation<CachedSnapshot?, Error>?
    private var started = false

    init(value: CachedSnapshot) { self.value = value }

    func waitUntilStarted() async {
        while !started { await Task.yield() }
    }

    func resumeLoad() {
        continuation?.resume(returning: value)
        continuation = nil
    }

    func load(scopeID: String) async throws -> CachedSnapshot? {
        started = true
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
        }
    }

    func save(_ snapshot: ViewerSnapshot, savedAt: Date, scopeID: String) {}
    func clear() {}
}

private enum TestCleanupError: Error { case expected }

private final class FailingDeleteTokenStore: TokenStoring {
    func readToken() throws -> String? { "existing-token" }
    func saveToken(_ token: String) throws {}
    func deleteToken() throws { throw TestCleanupError.expected }
}

private actor FailingClearCache: SnapshotCaching {
    func load(scopeID: String) -> CachedSnapshot? { nil }
    func save(_ snapshot: ViewerSnapshot, savedAt: Date, scopeID: String) {}
    func clear() throws { throw TestCleanupError.expected }
}

private actor SequencedViewerAPI: ViewerAPIClientProtocol {
    private let snapshot: ViewerSnapshot
    private var shouldFail = false

    init(snapshot: ViewerSnapshot) { self.snapshot = snapshot }

    func failFutureSnapshots() { shouldFail = true }

    func fetchHealth() async throws -> HealthResponse {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(HealthResponse.self, from: TestFixtures.healthJSON)
    }

    func fetchSnapshot() async throws -> ViewerSnapshot {
        if shouldFail { throw URLError(.notConnectedToInternet) }
        return snapshot
    }
}
