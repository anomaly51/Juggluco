import Charts
import SwiftUI

struct GlucoseChartView: View {
    let snapshot: ViewerSnapshot
    let effectiveServerNowMs: Int64
    let displayForecast: Bool
    @State private var window: ChartWindow = .sixHours

    private var serverNow: Date { Date(milliseconds: effectiveServerNowMs) }
    private var windowStart: Date { serverNow.addingTimeInterval(-window.hours * 60 * 60) }
    private var chartEnd: Date {
        guard displayForecast,
              let finalForecast = snapshot.forecast.points.last?.date else { return serverNow }
        return max(serverNow, finalForecast)
    }

    private var readings: [GlucoseReading] {
        var result = snapshot.glucoseHistory.filter { $0.date >= windowStart && $0.date <= serverNow }
        if let current = snapshot.currentGlucose,
           current.date >= windowStart,
           !result.contains(where: { $0.readingId == current.readingId }) {
            result.append(current)
        }
        return result.sorted(by: { $0.measuredAtMs < $1.measuredAtMs })
    }

    private var segmentedReadings: [SegmentedReading] {
        var segment = 0
        var previous: Int64?
        return readings.map { reading in
            if let previous, reading.measuredAtMs - previous > 10 * 60 * 1_000 {
                segment += 1
            }
            previous = reading.measuredAtMs
            return SegmentedReading(reading: reading, segment: segment)
        }
    }

    private var yDomain: ClosedRange<Double> {
        GlucoseChartDomain.range(
            actualMgDl: readings.map(\.glucoseMgDl),
            forecast: displayForecast ? snapshot.forecast.points : [],
            target: snapshot.targetRange
        )
    }

    var body: some View {
        SoftCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Динамика сахара")
                            .font(.headline)
                        Text("Зелёная зона: 4,2–9,0 ммоль/л")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    HStack(spacing: 12) {
                        LegendDot(color: .cyan, title: "CGM")
                        LegendDot(color: ViewerPalette.forecast, title: "Прогноз")
                    }
                }

                Picker("Период графика", selection: $window) {
                    ForEach(ChartWindow.allCases) { value in
                        Text(value.title).tag(value)
                    }
                }
                .pickerStyle(.segmented)

                if readings.isEmpty {
                    ContentUnavailableView(
                        "Нет истории за период",
                        systemImage: "chart.xyaxis.line",
                        description: Text("График появится после синхронизации Android с backend.")
                    )
                    .frame(height: 250)
                } else {
                    Chart {
                        RectangleMark(
                            xStart: .value("Начало", windowStart),
                            xEnd: .value("Конец", chartEnd),
                            yStart: .value("Нижняя цель", snapshot.targetRange.lowMgDl),
                            yEnd: .value("Верхняя цель", snapshot.targetRange.highMgDl)
                        )
                        .foregroundStyle(ViewerPalette.target.opacity(0.10))

                        ForEach(segmentedReadings) { point in
                            LineMark(
                                x: .value("Время", point.reading.date),
                                y: .value("Сахар", point.reading.glucoseMgDl),
                                series: .value("Непрерывный участок", point.segment)
                            )
                            .foregroundStyle(.cyan)
                            .lineStyle(StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
                        }

                        if displayForecast {
                            ForEach(snapshot.forecast.points) { point in
                                AreaMark(
                                    x: .value("Время прогноза", point.date),
                                    yStart: .value("Нижняя граница", point.lowMgDl),
                                    yEnd: .value("Верхняя граница", point.highMgDl)
                                )
                                .foregroundStyle(
                                    .linearGradient(
                                        colors: [ViewerPalette.forecast.opacity(0.30), ViewerPalette.forecast.opacity(0.06)],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    )
                                )

                                LineMark(
                                    x: .value("Время прогноза", point.date),
                                    y: .value("Медиана прогноза", point.medianMgDl)
                                )
                                .foregroundStyle(ViewerPalette.forecast)
                                .lineStyle(StrokeStyle(lineWidth: 2, dash: [6, 4]))
                            }
                        }

                        RuleMark(x: .value("Сейчас", serverNow))
                            .foregroundStyle(.secondary.opacity(0.6))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    }
                    .chartXScale(domain: windowStart...chartEnd)
                    .chartYScale(domain: yDomain)
                    .chartYAxis {
                        AxisMarks(position: .leading) { value in
                            AxisGridLine().foregroundStyle(.secondary.opacity(0.15))
                            AxisValueLabel {
                                if let mgDl = value.as(Double.self) {
                                    Text((mgDl / 18).formatted(.number.precision(.fractionLength(0...1))))
                                }
                            }
                        }
                    }
                    .chartXAxis {
                        AxisMarks(values: .automatic(desiredCount: 5)) {
                            AxisGridLine().foregroundStyle(.secondary.opacity(0.10))
                            AxisValueLabel(format: .dateTime.hour().minute())
                        }
                    }
                    .chartLegend(.hidden)
                    .frame(height: 285)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(chartAccessibilityLabel)
                }

                if snapshot.glucoseHistoryTruncated {
                    Label("История ограничена размером ответа сервера", systemImage: "scissors")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                Text("Разрывы линии означают паузу CGM больше 10 минут. Фиолетовая область — диапазон неопределённости, а не гарантия.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .privacySensitive()
    }

    private var chartAccessibilityLabel: String {
        var parts = ["График сахара за \(window.title)."]
        if let current = snapshot.newestReading {
            parts.append("Последнее значение \(current.mmolL.formatted(.number.precision(.fractionLength(1)))) миллимоль на литр.")
        }
        if displayForecast, let point = snapshot.forecast.points.last {
            parts.append("Через два часа медиана прогноза \((point.medianMgDl / 18).formatted(.number.precision(.fractionLength(1)))) миллимоль на литр.")
        } else {
            parts.append("Доступного прогноза нет.")
        }
        return parts.joined(separator: " ")
    }
}

private struct SegmentedReading: Identifiable {
    let reading: GlucoseReading
    let segment: Int
    var id: String { "\(segment)-\(reading.id)" }
}

private enum ChartWindow: Double, CaseIterable, Identifiable {
    case threeHours = 3
    case sixHours = 6
    case twelveHours = 12
    case day = 24

    var id: Double { rawValue }
    var hours: Double { rawValue }
    var title: String {
        switch self {
        case .threeHours: return "3 ч"
        case .sixHours: return "6 ч"
        case .twelveHours: return "12 ч"
        case .day: return "24 ч"
        }
    }
}

private struct LegendDot: View {
    let color: Color
    let title: String

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
