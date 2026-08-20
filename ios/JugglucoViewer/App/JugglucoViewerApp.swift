import SwiftUI
import UIKit

@main
struct JugglucoViewerApp: App {
    @StateObject private var model = ViewerAppModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var screenIsCaptured = UIScreen.main.isCaptured

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootTabView()
                    .environmentObject(model)
                    .privacySensitive()

                if scenePhase != .active {
                    PrivacyCover()
                        .transition(.opacity)
                        .zIndex(10)
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if screenIsCaptured && scenePhase == .active {
                    CaptureWarning()
                }
            }
            .onAppear {
                if scenePhase == .active { model.beginForegroundUpdates() }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    model.beginForegroundUpdates()
                } else {
                    model.endForegroundUpdates()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                screenIsCaptured = UIScreen.main.isCaptured
            }
        }
    }
}
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.043, blue: 0.055).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(.mint)
                Text("Juggluco Viewer")
                    .font(.title2.bold())
                Text("Медицинские данные скрыты")
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

private struct CaptureWarning: View {
    var body: some View {
        Label("Экран записывается — данные могут попасть в запись", systemImage: "record.circle.fill")
            .font(.footnote.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .padding(.horizontal)
            .foregroundStyle(.white)
            .background(Color.red)
            .accessibilityAddTraits(.isStaticText)
    }
}
