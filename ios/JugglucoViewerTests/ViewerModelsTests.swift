import XCTest
@testable import JugglucoViewer

final class ViewerModelsTests: XCTestCase {
    func testSnapshotDecodesExactViewerContractAndNormalizesOrdering() throws {
        let snapshot = try TestFixtures.snapshot()

        XCTAssertEqual(snapshot.apiVersion, "v1")
        XCTAssertEqual(snapshot.targetRange.lowMmolL, 4.2)
        XCTAssertEqual(snapshot.targetRange.highMmolL, 9.0)
        XCTAssertEqual(snapshot.currentGlucose?.ageMs, 300_000)
        XCTAssertEqual(snapshot.currentGlucose?.isStale, false)
        XCTAssertEqual(snapshot.glucoseHistory.map(\.readingId), ["cgm-old", "cgm-new"])
        XCTAssertTrue(snapshot.glucoseHistoryTruncated)
        XCTAssertFalse(snapshot.intakeEventsTruncated)
        XCTAssertEqual(snapshot.intakeEvents.first?.eventKind, .meal)
        XCTAssertEqual(snapshot.intakeEvents.first?.carbsG, 42)
        XCTAssertEqual(snapshot.forecast.points.last?.medianMgDl, 101)
    }

    func testCanonicalMgDlConvertsOnlyForDisplay() throws {
        let reading = try XCTUnwrap(TestFixtures.snapshot().currentGlucose)
        XCTAssertEqual(reading.glucoseMgDl, 108)
        XCTAssertEqual(reading.mmolL, 6, accuracy: 0.0001)
    }

    func testServerClockDrivesAgeAndStaleness() throws {
        let reading = try XCTUnwrap(TestFixtures.snapshot().currentGlucose)
        XCTAssertEqual(reading.age(relativeToServerTimeMs: 1_800_000), 300)
        XCTAssertFalse(reading.isReadingStale(relativeToServerTimeMs: 1_800_000))
        XCTAssertTrue(reading.isReadingStale(relativeToServerTimeMs: 2_500_000))
    }

    func testChartDomainDoesNotClipValidExtremeReadings() {
        let domain = GlucoseChartDomain.range(
            actualMgDl: [20, 600],
            forecast: [],
            target: .juggluco
        )
        XCTAssertLessThanOrEqual(domain.lowerBound, 20)
        XCTAssertGreaterThanOrEqual(domain.upperBound, 600)
    }
}
