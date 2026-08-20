import Foundation

protocol ViewerAPIClientProtocol: Sendable {
    func fetchHealth() async throws -> HealthResponse
    func fetchSnapshot() async throws -> ViewerSnapshot
}

enum ViewerAPIError: LocalizedError, Equatable {
    case invalidResponse
    case unauthorized
    case viewerAPIUnavailable
    case server(status: Int)
    case responseTooLarge
    case invalidData

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Сервер вернул неизвестный ответ."
        case .unauthorized: return "Ключ просмотра не принят сервером."
        case .viewerAPIUnavailable: return "Сервер ещё не поддерживает API для iOS Viewer."
        case .server(let status): return "Сервер временно недоступен (HTTP \(status))."
        case .responseTooLarge: return "Ответ сервера слишком большой."
        case .invalidData: return "Не удалось прочитать данные сервера."
        }
    }
}

final class ViewerAPIClient: ViewerAPIClientProtocol, @unchecked Sendable {
    static let maxResponseBytes = 12 * 1_024 * 1_024

    private let baseURL: URL
    private let token: String
    private let session: URLSession
    private let decoder: JSONDecoder
    private let redirectDelegate: RedirectRejectingSessionDelegate?

    init(baseURL: URL, token: String, session: URLSession? = nil) {
        self.baseURL = baseURL
        self.token = token
        if let session {
            self.redirectDelegate = nil
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 15
            configuration.timeoutIntervalForResource = 30
            configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            let redirectDelegate = RedirectRejectingSessionDelegate()
            self.redirectDelegate = redirectDelegate
            self.session = URLSession(
                configuration: configuration,
                delegate: redirectDelegate,
                delegateQueue: nil
            )
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        self.decoder = decoder
    }

    func fetchHealth() async throws -> HealthResponse {
        // Health is intentionally unauthenticated. Never attach the viewer token here.
        let request = URLRequest(url: endpoint("v1/health"), cachePolicy: .reloadIgnoringLocalCacheData)
        return try await perform(request, as: HealthResponse.self)
    }

    func fetchSnapshot() async throws -> ViewerSnapshot {
        var components = URLComponents(url: endpoint("v1/viewer/snapshot"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            // 1,500 covers an inclusive 24-hour window for one-minute sensors.
            // The backend chooses the window from its own clock, avoiding phone clock skew.
            URLQueryItem(name: "glucose_limit", value: "1500"),
            URLQueryItem(name: "event_limit", value: "100"),
        ]
        guard let url = components?.url else { throw ViewerAPIError.invalidResponse }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let snapshot = try await perform(request, as: ViewerSnapshot.self)
        return snapshot.normalized
    }

    private func endpoint(_ relativePath: String) -> URL {
        relativePath.split(separator: "/").reduce(baseURL) { partial, component in
            partial.appendingPathComponent(String(component), isDirectory: false)
        }
    }

    private func perform<Response: Decodable>(
        _ sourceRequest: URLRequest,
        as type: Response.Type
    ) async throws -> Response {
        var request = sourceRequest
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("JugglucoViewer/0.1", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ViewerAPIError.invalidResponse }
        guard data.count <= Self.maxResponseBytes else { throw ViewerAPIError.responseTooLarge }
        switch http.statusCode {
        case 200 ..< 300: break
        case 401, 403: throw ViewerAPIError.unauthorized
        case 404: throw ViewerAPIError.viewerAPIUnavailable
        default: throw ViewerAPIError.server(status: http.statusCode)
        }
        do {
            return try decoder.decode(type, from: data)
        } catch {
            // Deliberately do not include response bodies in errors: they contain health data.
            throw ViewerAPIError.invalidData
        }
    }
}

final class RedirectRejectingSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // A redirect must never move an Authorization header to another origin.
        // Server URLs are explicit; operators should configure the canonical URL.
        completionHandler(nil)
    }
}
