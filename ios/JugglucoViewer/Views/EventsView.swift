import SwiftUI

struct EventsView: View {
    @EnvironmentObject private var model: ViewerAppModel
    @State private var filter: EventFilter = .all

    private var events: [IntakeEvent] {
        let source = Array((model.snapshot?.intakeEvents ?? []).reversed())
        return source.filter { filter.matches($0) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if !model.isConfigured {
                    EmptyDataCard(
                        symbol: "lock.icloud.fill",
                        title: "Сервер не подключён",
                        detail: "Добавьте HTTPS-адрес и ключ просмотра в настройках."
                    )
                    .padding()
                } else if events.isEmpty {
                    ContentUnavailableView(
                        "Нет событий",
                        systemImage: filter.symbol,
                        description: Text(filter == .all ? "Еда и инсулин появятся после синхронизации Android." : "В выбранной категории пока ничего нет.")
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            if model.snapshot?.intakeEventsTruncated == true {
                                Label("Показана последняя часть истории событий", systemImage: "ellipsis.circle")
                                    .font(.footnote)
                                    .foregroundStyle(.orange)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            ForEach(events) { event in
                                SoftCard { EventRow(event: event, expanded: true) }
                            }
                        }
                        .padding()
                    }
                    .refreshable { await model.refresh() }
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("События")
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 8) {
                    Text("Последние 24 часа")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Picker("Фильтр событий", selection: $filter) {
                        ForEach(EventFilter.allCases) { item in
                            Text(item.title).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                .padding(.horizontal)
                .padding(.vertical, 10)
                .background(.bar)
            }
            .safeAreaInset(edge: .bottom) {
                Label("Только просмотр — изменить данные здесь нельзя", systemImage: "eye.fill")
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(.bar)
            }
        }
        .privacySensitive()
    }
}

struct EventRow: View {
    let event: IntakeEvent
    var expanded = false

    private var color: Color {
        switch event.eventKind {
        case .meal: return .orange
        case .rapid: return .blue
        case .long: return .indigo
        case .other: return .secondary
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: event.eventKind.symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 38, height: 38)
                .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline) {
                    Text(event.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(expanded ? 3 : 1)
                    Spacer(minLength: 8)
                    Text(event.date, format: .dateTime.hour().minute())
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if !event.amountText.isEmpty {
                    Text(event.amountText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if expanded {
                    Text(event.date, format: .dateTime.day().month(.wide).year())
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if event.eventKind == .meal, let duration = event.absorptionDurationMinutes {
                        Label("Оценка усвоения: около \(duration) мин", systemImage: "timer")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .privacySensitive()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        [event.eventKind.title, event.title, event.amountText, event.date.formatted(date: .abbreviated, time: .shortened)]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }
}

private enum EventFilter: String, CaseIterable, Identifiable {
    case all
    case meal
    case rapid
    case long

    var id: String { rawValue }
    var title: String {
        switch self {
        case .all: return "Все"
        case .meal: return "Еда"
        case .rapid: return "Быстрый"
        case .long: return "Долгий"
        }
    }
    var symbol: String {
        switch self {
        case .all: return "list.bullet.rectangle"
        case .meal: return "fork.knife"
        case .rapid: return "syringe"
        case .long: return "moon.stars"
        }
    }
    func matches(_ event: IntakeEvent) -> Bool {
        switch self {
        case .all: return true
        case .meal: return event.eventKind == .meal
        case .rapid: return event.eventKind == .rapid
        case .long: return event.eventKind == .long
        }
    }
}
