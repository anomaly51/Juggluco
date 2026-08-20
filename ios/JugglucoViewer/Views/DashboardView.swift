import SwiftUI

struct DashboardView: View {
    @EnvironmentObject private var model: ViewerAppModel

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 16) {
                    if !model.isConfigured {
                        NotConfiguredView()
                    } else if let snapshot = model.snapshot {
                        ConnectionStateView()
                        if let current = snapshot.newestReading {
                            CurrentGlucoseCard(
                                reading: current,
                                snapshot: snapshot,
                                effectiveServerNowMs: model.effectiveServerNowMs(for: snapshot)
                            )
                        } else {
                            EmptyDataCard(
                                symbol: "sensor.tag.radiowaves.forward.slash",
                                title: "Нет показаний",
                                detail: "Android ещё не передал показатели сахара на сервер."
                            )
                        }
                        GlucoseChartView(
                            snapshot: snapshot,
                            effectiveServerNowMs: model.effectiveServerNowMs(for: snapshot),
                            displayForecast: model.forecastIsCurrent(in: snapshot)
                        )
                        ForecastSummaryCard(
                            forecast: snapshot.forecast,
                            isCurrent: model.forecastIsCurrent(in: snapshot)
                        )
                        RecentEventsCard(events: Array(snapshot.intakeEvents.suffix(4).reversed()))
                        if model.historyIsIncomplete {
                            Label(
                                "Показана только часть доступной истории. Последние данные и прогноз не скрыты.",
                                systemImage: "ellipsis.circle"
                            )
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else if model.isRefreshing {
                        ProgressView("Получаем данные…")
                            .frame(maxWidth: .infinity)
                            .padding(.top, 80)
                    } else {
                        EmptyDataCard(
                            symbol: "wifi.exclamationmark",
                            title: "Данные пока недоступны",
                            detail: model.errorMessage ?? "Потяните экран вниз, чтобы повторить."
                        )
                    }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Сахар")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if model.isRefreshing {
                        ProgressView().controlSize(.small)
                            .accessibilityLabel("Обновление данных")
                    } else {
                        Button {
                            Task { await model.refresh() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .accessibilityLabel("Обновить")
                    }
                }
            }
            .refreshable { await model.refresh() }
        }
    }
}

private struct ConnectionStateView: View {
    @EnvironmentObject private var model: ViewerAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                switch model.contentSource {
                case .none:
                    StatePill(title: "Нет данных", symbol: "questionmark", color: .secondary)
                case .live:
                    StatePill(title: "Онлайн", symbol: "checkmark.circle.fill", color: ViewerPalette.target)
                case .cache(let savedAt):
                    StatePill(title: "Офлайн-копия", symbol: "internaldrive.fill", color: .orange)
                    Spacer()
                    Text(savedAt, style: .relative)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if model.currentReadingIsStale {
                    StatePill(title: "Показание устарело", symbol: "clock.badge.exclamationmark", color: .red)
                }
            }
            if let message = model.errorMessage {
                Label(message, systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            if let warning = model.cacheWarning {
                Label(warning, systemImage: "lock.trianglebadge.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }
}

private struct CurrentGlucoseCard: View {
    let reading: GlucoseReading
    let snapshot: ViewerSnapshot
    let effectiveServerNowMs: Int64

    private var state: (String, Color, String) {
        if reading.glucoseMgDl < snapshot.targetRange.lowMgDl {
            return ("Ниже цели", ViewerPalette.low, "arrow.down.circle.fill")
        }
        if reading.glucoseMgDl > snapshot.targetRange.highMgDl {
            return ("Выше цели", ViewerPalette.high, "arrow.up.circle.fill")
        }
        return ("В целевом диапазоне", ViewerPalette.target, "checkmark.circle.fill")
    }

    var body: some View {
        SoftCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("ТЕКУЩИЙ САХАР")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(reading.mmolL.formatted(.number.precision(.fractionLength(1))))
                                .font(.system(size: 52, weight: .bold, design: .rounded))
                                .minimumScaleFactor(0.65)
                            Text(reading.trendArrow)
                                .font(.system(size: 38, weight: .semibold))
                                .foregroundStyle(state.1)
                            Text("ммоль/л")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Image(systemName: state.2)
                        .font(.title)
                        .foregroundStyle(state.1)
                        .accessibilityHidden(true)
                }
                HStack {
                    StatePill(title: state.0, symbol: state.2, color: state.1)
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(reading.glucoseMgDl.formatted(.number.precision(.fractionLength(0)))) mg/dL")
                        Text(ageDescription(reading.age(relativeToServerTimeMs: effectiveServerNowMs)))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
        .privacySensitive()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "Текущий сахар \(reading.mmolL.formatted(.number.precision(.fractionLength(1)))) миллимоль на литр, \(state.0), направление \(reading.trendArrow), \(ageDescription(reading.age(relativeToServerTimeMs: effectiveServerNowMs)))"
        )
    }
}

private struct ForecastSummaryCard: View {
    let forecast: GlucoseForecast
    let isCurrent: Bool

    var body: some View {
        SoftCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("Прогноз на 2 часа", systemImage: "sparkles")
                        .font(.headline)
                    Spacer()
                    StatePill(
                        title: statusTitle,
                        symbol: forecast.canDisplayTrajectory && isCurrent ? "waveform.path.ecg" : "exclamationmark.triangle.fill",
                        color: forecast.canDisplayTrajectory && isCurrent ? ViewerPalette.forecast : .orange
                    )
                }
                if forecast.canDisplayTrajectory && isCurrent {
                    ProgressView(value: forecast.confidence)
                        .tint(ViewerPalette.forecast)
                    Text("Технический индикатор надёжности: \(forecast.confidence.formatted(.number.precision(.fractionLength(2)))) из 1")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(forecast.conditionalNotice)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var statusTitle: String {
        if forecast.canDisplayTrajectory && !isCurrent { return "Протух" }
        switch forecast.status {
        case "ready": return "Готов"
        case "cold_start": return "Базовая модель"
        case "low_confidence": return "Мало данных"
        case "stale": return "Устарел"
        case "no_data": return "Нет данных"
        default: return "Экспериментальный"
        }
    }
}

private struct RecentEventsCard: View {
    let events: [IntakeEvent]

    var body: some View {
        SoftCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("Последние события", systemImage: "clock.arrow.circlepath")
                    .font(.headline)
                if events.isEmpty {
                    Text("Еда и инсулин пока не переданы.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(events) { event in
                        EventRow(event: event)
                        if event.id != events.last?.id { Divider() }
                    }
                }
            }
        }
        .privacySensitive()
    }
}

private struct NotConfiguredView: View {
    var body: some View {
        EmptyDataCard(
            symbol: "lock.icloud.fill",
            title: "Подключите сервер",
            detail: "Откройте «Настройки», укажите HTTPS-адрес backend и отдельный ключ только для просмотра."
        )
        .padding(.top, 50)
    }
}

struct EmptyDataCard: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        SoftCard {
            VStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 38))
                    .foregroundStyle(.mint)
                Text(title).font(.title3.bold())
                Text(detail)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .accessibilityElement(children: .combine)
    }
}
