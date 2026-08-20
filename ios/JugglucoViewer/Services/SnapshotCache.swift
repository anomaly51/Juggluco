import Foundation

protocol SnapshotCaching: Sendable {
    func load(scopeID: String) async throws -> CachedSnapshot?
    func save(_ snapshot: ViewerSnapshot, savedAt: Date, scopeID: String) async throws
    func clear() async throws
}

struct CachedSnapshot: Codable, Equatable, Sendable {
    let scopeID: String
    let savedAt: Date
    let snapshot: ViewerSnapshot
}

enum SnapshotCacheError: Error {
    case backupExclusionUnavailable
    case fileProtectionUnavailable
}

actor FileSnapshotCache: SnapshotCaching {
    private let fileManager: FileManager
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(fileManager: FileManager = .default, fileURL: URL? = nil) {
        self.fileManager = fileManager
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            self.fileURL = caches
                .appendingPathComponent("JugglucoViewer", isDirectory: true)
                .appendingPathComponent("last-good-snapshot.json", isDirectory: false)
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970
        self.encoder = encoder
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        self.decoder = decoder
    }

    func load(scopeID: String) throws -> CachedSnapshot? {
        guard fileManager.fileExists(atPath: fileURL.path) else { return nil }
        do {
            try verifyFileSecurity()
            let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
            let cached = try decoder.decode(CachedSnapshot.self, from: data)
            guard cached.scopeID == scopeID else {
                try removeSnapshotIfPresent()
                return nil
            }
            return cached
        } catch {
            try removeSnapshotIfPresent()
            throw error
        }
    }

    func save(_ snapshot: ViewerSnapshot, savedAt: Date, scopeID: String) throws {
        let directory = fileURL.deletingLastPathComponent()
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        do {
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: directory.path
            )
            var mutableDirectory = directory
            try mutableDirectory.setResourceValues(resourceValues)
            guard try mutableDirectory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true else {
                throw SnapshotCacheError.backupExclusionUnavailable
            }
            let directoryAttributes = try fileManager.attributesOfItem(atPath: directory.path)
            guard directoryAttributes[.protectionKey] as? FileProtectionType == .complete else {
                throw SnapshotCacheError.fileProtectionUnavailable
            }
        } catch {
            try removeSnapshotIfPresent()
            throw error
        }

        let data = try encoder.encode(
            CachedSnapshot(scopeID: scopeID, savedAt: savedAt, snapshot: snapshot)
        )
        do {
            try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: fileURL.path
            )
            var mutableFile = fileURL
            try mutableFile.setResourceValues(resourceValues)
            guard try mutableFile.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true else {
                throw SnapshotCacheError.backupExclusionUnavailable
            }
            try verifyFileSecurity()
        } catch {
            // Fail closed: never retain health data in a location whose backup
            // exclusion could not be established and verified.
            try removeSnapshotIfPresent()
            throw error
        }
    }

    func clear() throws {
        try removeSnapshotIfPresent()
    }

    private func verifyFileSecurity() throws {
        let resources = try fileURL.resourceValues(forKeys: [.isExcludedFromBackupKey])
        guard resources.isExcludedFromBackup == true else {
            throw SnapshotCacheError.backupExclusionUnavailable
        }
        let attributes = try fileManager.attributesOfItem(atPath: fileURL.path)
        guard attributes[.protectionKey] as? FileProtectionType == .complete else {
            throw SnapshotCacheError.fileProtectionUnavailable
        }
    }

    private func removeSnapshotIfPresent() throws {
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }
}
