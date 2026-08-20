import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: ViewerAppModel
    @State private var baseURL = ""
    @State private var token = ""
    @State private var localError: String?
    @State private var saveConfirmation: String?
    @State private var isSaving = false
    @State private var showDisconnectConfirmation = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://example.com", text: $baseURL)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .privacySensitive()
                        .accessibilityLabel("HTTPS-адрес backend")

                    SecureField(
                        model.hasStoredToken ? "Ключ сохранён — оставьте пустым" : "Ключ только для просмотра",
                        text: $token
                    )
                    .textContentType(.password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .privacySensitive()
                    .accessibilityHint("Ключ хранится только в Keychain этого устройства")

                    Button {
                        save()
                    } label: {
                        HStack {
                            Label("Сохранить и проверить", systemImage: "checkmark.shield.fill")
                            Spacer()
                            if isSaving { ProgressView().controlSize(.small) }
                        }
                    }
                    .disabled(isSaving)

                    if let localError {
                        Label(localError, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    } else if let saveConfirmation {
                        Label(saveConfirmation, systemImage: "checkmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(ViewerPalette.target)
                    }
                    if let connectionError = model.errorMessage {
                        Label(connectionError, systemImage: "wifi.exclamationmark")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                } header: {
                    Text("Удалённый backend")
                } footer: {
                    Text("В релизной сборке разрешён только HTTPS. Используйте отдельный JUGGLUCO_VIEWER_TOKEN: ключ Android с правами записи здесь не требуется.")
                }

                Section("Состояние") {
                    LabeledContent("API просмотра") {
                        Text(apiStateTitle)
                            .foregroundStyle(apiStateColor)
                    }
                    if let health = model.health {
                        LabeledContent("База данных", value: health.database == "ok" ? "Доступна" : "Ошибка")
                        LabeledContent("Viewer-аутентификация", value: health.viewerAuthConfigured == true ? "Настроена" : "Не подтверждена")
                        LabeledContent("Версия API", value: health.apiVersion)
                    }
                    Button("Проверить сейчас") {
                        Task { await model.refresh() }
                    }
                    .disabled(!model.isConfigured || model.isRefreshing)
                }

                Section("Режим приложения") {
                    Label("Только просмотр", systemImage: "eye.fill")
                    Text("Приложение получает сахар, прогноз, еду и быстрый/длительный инсулин. Оно не создаёт и не изменяет записи, не рассчитывает дозы и не выполняет лечение.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text("Обновление выполняется при открытом приложении, раз в минуту и жестом потянуть вниз. iOS может останавливать фоновые процессы, поэтому это не канал реального времени и не замена медицинской сигнализации.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Label("Перед решениями о лечении и изменением терапии обязательно консультируйтесь с лечащим врачом.", systemImage: "stethoscope")
                        .font(.footnote.weight(.semibold))
                    Text("Источник: CGM и события, переданные Android на backend. Прогноз — статическая экспериментальная модель с интервалом неопределённости; синхронизация может запаздывать или иметь пропуски.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Конфиденциальность") {
                    Label("Ключ хранится в Keychain и доступен только после разблокировки", systemImage: "key.fill")
                    Label("Офлайн-копия защищена Data Protection и исключена из backup", systemImage: "lock.doc.fill")
                    Label("В переключателе приложений данные закрываются", systemImage: "rectangle.on.rectangle.slash")
                    Text("iOS не позволяет приложению гарантированно запретить скриншоты. При активной записи экрана Viewer показывает предупреждение.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Справка") {
                    Link(
                        "Политика конфиденциальности",
                        destination: URL(string: "https://github.com/anomaly51/Juggluco/blob/primary/ios/PRIVACY.md")!
                    )
                    Link(
                        "Документация и сообщения об ошибках",
                        destination: URL(string: "https://github.com/anomaly51/Juggluco/issues")!
                    )
                }

                if model.hasStoredToken {
                    Section {
                        Button("Отключить этот iPhone", role: .destructive) {
                            showDisconnectConfirmation = true
                        }
                    } footer: {
                        Text("Удалится только локальный ключ и офлайн-кэш. Данные backend не изменятся.")
                    }
                }
            }
            .navigationTitle("Настройки")
            .onAppear { baseURL = model.serverURLText }
            .confirmationDialog(
                "Удалить подключение и локальную копию?",
                isPresented: $showDisconnectConfirmation,
                titleVisibility: .visible
            ) {
                Button("Отключить", role: .destructive) {
                    Task {
                        do {
                            try await model.disconnect()
                            token = ""
                            saveConfirmation = "iPhone отключён"
                        } catch {
                            localError = error.localizedDescription
                        }
                    }
                }
                Button("Отмена", role: .cancel) {}
            }
        }
    }

    private var apiStateTitle: String {
        switch model.contentSource {
        case .live: return "Подключён"
        case .cache: return "Офлайн"
        case .none: return model.isConfigured ? "Не проверен" : "Не настроен"
        }
    }

    private var apiStateColor: Color {
        switch model.contentSource {
        case .live: return ViewerPalette.target
        case .cache: return .orange
        case .none: return .secondary
        }
    }

    private func save() {
        isSaving = true
        localError = nil
        saveConfirmation = nil
        Task {
            defer { isSaving = false }
            do {
                try await model.saveConfiguration(baseURLText: baseURL, newToken: token)
                baseURL = model.serverURLText
                token = ""
                saveConfirmation = model.errorMessage == nil
                    ? "Подключение проверено"
                    : "Настройки сохранены; сервер пока не ответил"
                model.beginForegroundUpdates()
            } catch {
                localError = error.localizedDescription
            }
        }
    }
}
