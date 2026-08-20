import Foundation
import CryptoKit

struct ViewerConfiguration: Equatable, Sendable {
    let baseURL: URL
    let token: String

    var cacheScopeID: String {
        let material = Data("\(baseURL.absoluteString)\u{0}\(token)".utf8)
        return SHA256.hash(data: material).map { String(format: "%02x", $0) }.joined()
    }
}

enum ServerURLValidationError: LocalizedError, Equatable {
    case empty
    case malformed
    case credentialsNotAllowed
    case insecureTransport

    var errorDescription: String? {
        switch self {
        case .empty: return "Укажите адрес сервера."
        case .malformed: return "Проверьте адрес сервера."
        case .credentialsNotAllowed: return "Логин и пароль нельзя помещать в адрес сервера."
        case .insecureTransport: return "Удалённое подключение должно использовать HTTPS."
        }
    }
}

enum ServerURLValidator {
    static func validate(_ rawValue: String) throws -> URL {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ServerURLValidationError.empty }
        guard var components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let host = components.host,
              !host.isEmpty else {
            throw ServerURLValidationError.malformed
        }
        guard components.user == nil && components.password == nil else {
            throw ServerURLValidationError.credentialsNotAllowed
        }
        guard components.query == nil && components.fragment == nil else {
            throw ServerURLValidationError.malformed
        }

        let secure = scheme == "https"
        #if DEBUG
        let debugLocalHost = scheme == "http" && isLocalDevelopmentHost(host)
        guard secure || debugLocalHost else { throw ServerURLValidationError.insecureTransport }
        #else
        guard secure else { throw ServerURLValidationError.insecureTransport }
        #endif

        var path = components.path
        while path.count > 1 && path.hasSuffix("/") { path.removeLast() }
        components.path = path == "/" ? "" : path
        guard let result = components.url else { throw ServerURLValidationError.malformed }
        return result
    }

    private static func isLocalDevelopmentHost(_ host: String) -> Bool {
        let normalized = host.lowercased()
        return normalized == "localhost" || normalized == "127.0.0.1" ||
            normalized == "::1" || normalized.hasSuffix(".local")
    }
}

final class ConfigurationStore {
    private let defaults: UserDefaults
    private let key = "viewer.backend.baseURL"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadBaseURLText() -> String {
        defaults.string(forKey: key) ?? ""
    }

    func saveBaseURL(_ url: URL) {
        defaults.set(url.absoluteString, forKey: key)
    }

    func clearBaseURL() {
        defaults.removeObject(forKey: key)
    }
}
