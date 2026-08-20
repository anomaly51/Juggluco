import XCTest
@testable import JugglucoViewer

final class ViewerAPIClientTests: XCTestCase {
    override func setUp() {
        super.setUp()
        MockURLProtocol.requestHandler = nil
    }

    func testSnapshotUsesOnlyGETAndBearerNeverAppearsInURL() async throws {
        let token = "dedicated-viewer-secret"
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/root/v1/viewer/snapshot")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
            XCTAssertFalse(request.url?.absoluteString.contains(token) ?? true)
            let query = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
            XCTAssertEqual(query.queryItems?.first(where: { $0.name == "glucose_limit" })?.value, "1500")
            XCTAssertNil(query.queryItems?.first(where: { $0.name == "from_ms" }))
            XCTAssertNil(query.queryItems?.first(where: { $0.name == "to_ms" }))
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, TestFixtures.snapshotJSON)
        }

        let client = makeClient(token: token)
        _ = try await client.fetchSnapshot()
    }

    func testHealthNeverReceivesAuthorization() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/root/v1/health")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, TestFixtures.healthJSON)
        }

        let health = try await makeClient(token: "must-not-leak").fetchHealth()
        XCTAssertTrue(health.isReady)
    }

    func testUnauthorizedHasNonSensitiveError() async {
        MockURLProtocol.requestHandler = { request in
            (HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!, Data("private body".utf8))
        }
        do {
            _ = try await makeClient(token: "secret").fetchSnapshot()
            XCTFail("Expected unauthorized")
        } catch {
            XCTAssertEqual(error as? ViewerAPIError, .unauthorized)
            XCTAssertFalse(error.localizedDescription.contains("private body"))
            XCTAssertFalse(error.localizedDescription.contains("secret"))
        }
    }

    func testProductionDelegateRejectsRedirect() {
        let delegate = RedirectRejectingSessionDelegate()
        let original = URLRequest(url: URL(string: "https://viewer.example/v1/viewer/snapshot")!)
        let task = URLSession.shared.dataTask(with: original)
        let response = HTTPURLResponse(
            url: original.url!,
            statusCode: 307,
            httpVersion: nil,
            headerFields: ["Location": "https://other.example/steal"]
        )!
        var redirectedRequest: URLRequest? = URLRequest(url: URL(string: "https://sentinel.invalid")!)
        delegate.urlSession(
            .shared,
            task: task,
            willPerformHTTPRedirection: response,
            newRequest: URLRequest(url: URL(string: "https://other.example/steal")!),
            completionHandler: { redirectedRequest = $0 }
        )
        XCTAssertNil(redirectedRequest)
    }

    private func makeClient(token: String) -> ViewerAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return ViewerAPIClient(
            baseURL: URL(string: "https://viewer.example/root")!,
            token: token,
            session: session
        )
    }
}

private final class MockURLProtocol: URLProtocol {
    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.requestHandler else {
            XCTFail("Missing request handler")
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
