import SwiftUI

enum ViewerPalette {
    static let low = Color(red: 0.20, green: 0.63, blue: 1.0)
    static let target = Color(red: 0.15, green: 0.77, blue: 0.57)
    static let high = Color(red: 1.0, green: 0.56, blue: 0.23)
    static let forecast = Color(red: 0.69, green: 0.45, blue: 1.0)
}
struct SoftCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(.white.opacity(0.07))
            }
    }
}

struct StatePill: View {
    let title: String
    let symbol: String
    let color: Color

    var body: some View {
        Label(title, systemImage: symbol)
            .font(.caption.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(color.opacity(0.14), in: Capsule())
            .accessibilityElement(children: .combine)
    }
}

func ageDescription(_ age: TimeInterval) -> String {
    let seconds = max(0, Int(age))
    if seconds < 60 { return "только что" }
    let minutes = seconds / 60
    if minutes < 60 { return "\(minutes) мин назад" }
    let hours = minutes / 60
    let remainder = minutes % 60
    return remainder == 0 ? "\(hours) ч назад" : "\(hours) ч \(remainder) мин назад"
}
