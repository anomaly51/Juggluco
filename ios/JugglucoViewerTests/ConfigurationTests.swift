import XCTest
@testable import JugglucoViewer

final class ConfigurationTests: XCTestCase {
    func testHTTPSURLIsNormalized() throws {
        let url = try ServerURLValidator.validate("  https://example.com/backend/  ")
        XCTAssertEqual(url.absoluteString, "https://example.com/backend")
    }

    func testRemoteHTTPIsRejectedEvenInDebug() {
        XCTAssertThrowsError(try ServerURLValidator.validate("http://example.com")) { error in
            XCTAssertEqual(error as? ServerURLValidationError, .insecureTransport)
        }
    }

    func testCredentialsAndQueryAreRejected() {
        XCTAssertThrowsError(try ServerURLValidator.validate("https://name:secret@example.com"))
        XCTAssertThrowsError(try ServerURLValidator.validate("https://example.com?token=secret"))
    }

    func testCacheScopeChangesWithoutContainingToken() throws {
        let first = ViewerConfiguration(
            baseURL: try XCTUnwrap(URL(string: "https://one.example")),
            token: "viewer-secret-one"
        )
        let second = ViewerConfiguration(
            baseURL: first.baseURL,
            token: "viewer-secret-two"
        )
        XCTAssertNotEqual(first.cacheScopeID, second.cacheScopeID)
        XCTAssertFalse(first.cacheScopeID.contains(first.token))
        XCTAssertEqual(first.cacheScopeID.count, 64)
    }
}
