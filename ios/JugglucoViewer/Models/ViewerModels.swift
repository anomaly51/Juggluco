import Foundation

struct ViewerSnapshot: Codable, Equatable, Sendable {
    let apiVersion: String
    let serverTimeMs: Int64
    let fromMs: Int64
    let toMs: Int64
    let targetRange: TargetRange
    let currentGlucose: GlucoseReading?
    let glucoseHistory: [GlucoseReading]
    let glucoseHistoryOrder: String
    let glucoseHistoryTruncated: Bool
    let intakeEvents: [IntakeEvent]
    let intakeEventsOrder: String
    let intakeEventsTruncated: Bool
    let forecast: GlucoseForecast

    var newestReading: GlucoseReading? {
        currentGlucose ?? glucoseHistory.max(by: { $0.measuredAtMs < $1.measuredAtMs })
    }

    var normalized: ViewerSnapshot {
        ViewerSnapshot(
            apiVersion: apiVersion,
            serverTimeMs: serverTimeMs,
            fromMs: fromMs,
            toMs: toMs,
            targetRange: targetRange,
            currentGlucose: currentGlucose,
            glucoseHistory: glucoseHistory.sorted(by: { $0.measuredAtMs < $1.measuredAtMs }),
            glucoseHistoryOrder: "oldest_first",
            glucoseHistoryTruncated: glucoseHistoryTruncated,
            intakeEvents: intakeEvents
                .filter { !$0.deleted }
                .sorted(by: { $0.occurredAtMs < $1.occurredAtMs }),
            intakeEventsOrder: "oldest_first",
            intakeEventsTruncated: intakeEventsTruncated,
            forecast: forecast.normalized
        )
    }
}

struct TargetRange: Codable, Equatable, Sendable {
    let lowMgDl: Double
    let highMgDl: Double
    let lowMmolL: Double
    let highMmolL: Double

    static let juggluco = TargetRange(
        lowMgDl: 75.6,
        highMgDl: 162.0,
        lowMmolL: 4.2,
        highMmolL: 9.0
    )

    func contains(mgDl: Double) -> Bool {
        mgDl >= lowMgDl && mgDl <= highMgDl
    }
}

struct GlucoseReading: Codable, Equatable, Identifiable, Sendable {
    let readingId: String
    let measuredAtMs: Int64
    let glucoseMgDl: Double
    let trendMgDlMin: Double?
    let sensorId: String?
    let sensorGeneration: String?
    let quality: Double?
    let utcOffsetMinutes: Int?
    let receivedAtMs: Int64
    let ageMs: Int64?
    let isStale: Bool?

    var id: String { readingId }
    var date: Date { Date(milliseconds: measuredAtMs) }
    var mmolL: Double { glucoseMgDl / 18.0 }

    var trendArrow: String {
        guard let trendMgDlMin else { return "—" }
        switch trendMgDlMin {
        case 3...: return "↑"
        case 1...: return "↗"
        case -1..<1: return "→"
        case -3 ..< -1: return "↘"
        default: return "↓"
        }
    }

    func isReadingStale(
        relativeToServerTimeMs serverTimeMs: Int64,
        thresholdMs: Int64 = 15 * 60 * 1_000
    ) -> Bool {
        if isStale == true { return true }
        return serverTimeMs - measuredAtMs > thresholdMs
    }

    func age(relativeToServerTimeMs serverTimeMs: Int64) -> TimeInterval {
        TimeInterval(max(0, serverTimeMs - measuredAtMs)) / 1_000
    }
}

struct GlucoseForecast: Codable, Equatable, Sendable {
    let status: String
    let generatedAtMs: Int64
    let basedOnReadingAtMs: Int64?
    let basedOnGlucoseMgDl: Double?
    let horizonMinutes: Int
    let modelVersion: String
    let confidence: Double
    let points: [ForecastPoint]
    let activities: [ForecastActivity]
    let conditionalNotice: String

    var normalized: GlucoseForecast {
        GlucoseForecast(
            status: status,
            generatedAtMs: generatedAtMs,
            basedOnReadingAtMs: basedOnReadingAtMs,
            basedOnGlucoseMgDl: basedOnGlucoseMgDl,
            horizonMinutes: horizonMinutes,
            modelVersion: modelVersion,
            confidence: confidence,
            points: points.sorted(by: { $0.atMs < $1.atMs }),
            activities: activities,
            conditionalNotice: conditionalNotice
        )
    }

    var canDisplayTrajectory: Bool {
        !points.isEmpty && status != "no_data" && status != "stale"
    }
}

struct ForecastPoint: Codable, Equatable, Identifiable, Sendable {
    let atMs: Int64
    let medianMgDl: Double
    let lowMgDl: Double
    let highMgDl: Double

    var id: Int64 { atMs }
    var date: Date { Date(milliseconds: atMs) }
}

struct ForecastActivity: Codable, Equatable, Identifiable, Sendable {
    let eventId: String
    let kind: String
    let label: String
    let startMs: Int64
    let peakMs: Int64
    let endMs: Int64
    let strength: Double
    let confidence: Double
    let amount: Double
    let unit: String
    let profileSource: String
    let profileConfidence: Double
    let onsetMs: Int64?
    let peakLowMs: Int64?
    let peakHighMs: Int64?
    let endLowMs: Int64?
    let endHighMs: Int64?
    let attributionConfidence: Double?
    let identifiability: String?
    let actionModel: String?
    let overlapCount: Int?

    var id: String { eventId }
}

struct IntakeEvent: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let kind: String
    let occurredAtMs: Int64
    let mealText: String?
    let carbsG: Double?
    let portionG: Double?
    let originalPortionG: Double?
    let originalCarbsG: Double?
    let carbsSource: String?
    let insulinUnits: Double?
    let insulinType: String?
    let insulinName: String?
    let aiConfidence: Double
    let absorptionSpeed: Double?
    let absorptionPeakMinutes: Int?
    let absorptionDurationMinutes: Int?
    let absorptionConfidence: Double?
    let updatedAtMs: Int64

    // Kept optional so a later viewer contract can expose audited tombstones
    // without making an older cached snapshot unreadable.
    let deleted: Bool

    enum CodingKeys: String, CodingKey {
        case id, kind, occurredAtMs, mealText, carbsG, portionG
        case originalPortionG, originalCarbsG, carbsSource
        case insulinUnits, insulinType, insulinName, aiConfidence
        case absorptionSpeed, absorptionPeakMinutes, absorptionDurationMinutes
        case absorptionConfidence, updatedAtMs, deleted
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        kind = try values.decode(String.self, forKey: .kind)
        occurredAtMs = try values.decode(Int64.self, forKey: .occurredAtMs)
        mealText = try values.decodeIfPresent(String.self, forKey: .mealText)
        carbsG = try values.decodeIfPresent(Double.self, forKey: .carbsG)
        portionG = try values.decodeIfPresent(Double.self, forKey: .portionG)
        originalPortionG = try values.decodeIfPresent(Double.self, forKey: .originalPortionG)
        originalCarbsG = try values.decodeIfPresent(Double.self, forKey: .originalCarbsG)
        carbsSource = try values.decodeIfPresent(String.self, forKey: .carbsSource)
        insulinUnits = try values.decodeIfPresent(Double.self, forKey: .insulinUnits)
        insulinType = try values.decodeIfPresent(String.self, forKey: .insulinType)
        insulinName = try values.decodeIfPresent(String.self, forKey: .insulinName)
        aiConfidence = try values.decodeIfPresent(Double.self, forKey: .aiConfidence) ?? 0
        absorptionSpeed = try values.decodeIfPresent(Double.self, forKey: .absorptionSpeed)
        absorptionPeakMinutes = try values.decodeIfPresent(Int.self, forKey: .absorptionPeakMinutes)
        absorptionDurationMinutes = try values.decodeIfPresent(Int.self, forKey: .absorptionDurationMinutes)
        absorptionConfidence = try values.decodeIfPresent(Double.self, forKey: .absorptionConfidence)
        updatedAtMs = try values.decode(Int64.self, forKey: .updatedAtMs)
        deleted = try values.decodeIfPresent(Bool.self, forKey: .deleted) ?? false
    }

    var date: Date { Date(milliseconds: occurredAtMs) }
    var eventKind: EventKind { EventKind(rawBackendValue: kind) }

    var title: String {
        switch eventKind {
        case .meal: return mealText?.nonEmpty ?? "Приём пищи"
        case .rapid: return insulinName?.nonEmpty ?? "Быстрый инсулин"
        case .long: return insulinName?.nonEmpty ?? "Длительный инсулин"
        case .other: return "Событие"
        }
    }

    var amountText: String {
        switch eventKind {
        case .meal:
            let carbs = carbsG.map { "\($0.formatted(.number.precision(.fractionLength(0...1)))) г углеводов" }
            let portion = portionG.map { "порция \($0.formatted(.number.precision(.fractionLength(0...0)))) г" }
            return [carbs, portion].compactMap { $0 }.joined(separator: " · ")
        case .rapid, .long:
            return insulinUnits.map { "\($0.formatted(.number.precision(.fractionLength(0...2)))) ЕД" } ?? "Доза не указана"
        case .other:
            return ""
        }
    }
}

enum EventKind: String, CaseIterable, Identifiable, Sendable {
    case meal
    case rapid
    case long
    case other

    init(rawBackendValue: String) {
        self = EventKind(rawValue: rawBackendValue) ?? .other
    }

    var id: String { rawValue }
    var title: String {
        switch self {
        case .meal: return "Еда"
        case .rapid: return "Быстрый"
        case .long: return "Длительный"
        case .other: return "Другое"
        }
    }

    var symbol: String {
        switch self {
        case .meal: return "fork.knife"
        case .rapid: return "syringe.fill"
        case .long: return "moon.stars.fill"
        case .other: return "circle.fill"
        }
    }
}

enum GlucoseChartDomain {
    static func range(
        actualMgDl: [Double],
        forecast: [ForecastPoint],
        target: TargetRange
    ) -> ClosedRange<Double> {
        var values = actualMgDl
        values += forecast.flatMap { [$0.lowMgDl, $0.highMgDl] }
        values += [target.lowMgDl, target.highMgDl]
        let low = max(20, (values.min() ?? 60) - 18)
        let high = min(620, (values.max() ?? 180) + 18)
        return low...max(low + 40, high)
    }
}

struct HealthResponse: Codable, Equatable, Sendable {
    let status: String
    let apiVersion: String
    let database: String
    let authConfigured: Bool
    let aiConfigured: Bool
    let viewerAuthConfigured: Bool?

    var isReady: Bool { database == "ok" && viewerAuthConfigured == true }
}

extension Date {
    init(milliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1_000)
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
