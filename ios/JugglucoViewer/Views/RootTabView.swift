import SwiftUI

struct RootTabView: View {
    var body: some View {
        TabView {
            DashboardView()
                .tabItem { Label("Сахар", systemImage: "waveform.path.ecg") }

            EventsView()
                .tabItem { Label("События", systemImage: "list.bullet.rectangle") }

            SettingsView()
                .tabItem { Label("Настройки", systemImage: "gearshape.fill") }
        }
        .tint(.mint)
    }
}
