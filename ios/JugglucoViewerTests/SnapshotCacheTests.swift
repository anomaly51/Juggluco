import Foundation
import XCTest
@testable import JugglucoViewer

final class SnapshotCacheTests: XCTestCase {
    func testCacheRoundTripIsProtectedExcludedAndScopeBound() async throws {
        let manager = FileManager.default
        let directory = manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("JugglucoViewerTests-\(UUID().uuidString)", isDirectory: true)
        let file = directory.appendingPathComponent("snapshot.json")
        defer { try? manager.removeItem(at: directory) }

        let cache = FileSnapshotCache(fileManager: manager, fileURL: file)
        let snapshot = try TestFixtures.snapshot()
        try await cache.save(snapshot, savedAt: Date(timeIntervalSince1970: 100), scopeID: "scope-a")

        let loaded = try await cache.load(scopeID: "scope-a")
        XCTAssertEqual(loaded?.snapshot.currentGlucose?.glucoseMgDl, 108)
        XCTAssertEqual(
            try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup,
            true
        )
        let attributes = try manager.attributesOfItem(atPath: file.path)
        XCTAssertEqual(attributes[.protectionKey] as? FileProtectionType, .complete)

        let wrongScope = try await cache.load(scopeID: "scope-b")
        XCTAssertNil(wrongScope)
        XCTAssertFalse(manager.fileExists(atPath: file.path))
    }
}
