import Foundation
import Security

protocol TokenStoring {
    func readToken() throws -> String?
    func saveToken(_ token: String) throws
    func deleteToken() throws
}
enum KeychainTokenError: LocalizedError {
    case invalidData
    case unexpectedStatus(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidData: return "Сохранённый ключ доступа повреждён."
        case .unexpectedStatus: return "Не удалось открыть безопасное хранилище."
        }
    }
}

final class KeychainTokenStore: TokenStoring {
    private let service: String
    private let account: String

    init(
        service: String = Bundle.main.bundleIdentifier ?? "app.juggluco.viewer",
        account: String = "viewer-token"
    ) {
        self.service = service
        self.account = account
    }

    func readToken() throws -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainTokenError.unexpectedStatus(status) }
        guard let data = item as? Data,
              let token = String(data: data, encoding: .utf8),
              !token.isEmpty else {
            throw KeychainTokenError.invalidData
        }
        return token
    }

    func saveToken(_ token: String) throws {
        let normalized = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = normalized.data(using: .utf8), !normalized.isEmpty else {
            throw KeychainTokenError.invalidData
        }
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ] as CFDictionary
            let updateStatus = SecItemUpdate(baseQuery as CFDictionary, update)
            guard updateStatus == errSecSuccess else {
                throw KeychainTokenError.unexpectedStatus(updateStatus)
            }
            return
        }
        guard status == errSecSuccess else { throw KeychainTokenError.unexpectedStatus(status) }
    }

    func deleteToken() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainTokenError.unexpectedStatus(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
