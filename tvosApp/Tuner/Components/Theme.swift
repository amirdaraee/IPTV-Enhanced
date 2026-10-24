import SwiftUI

enum Palette {
    static let background = Color(red: 0.031, green: 0.043, blue: 0.071)
    static let surface = Color(red: 0.067, green: 0.090, blue: 0.137)
    static let surfaceHigh = Color(red: 0.102, green: 0.133, blue: 0.196)
    static let surfaceFocus = Color(red: 0.149, green: 0.196, blue: 0.290)
    static let outline = Color(red: 0.165, green: 0.204, blue: 0.275)
    static let accent = Color(red: 0.369, green: 0.918, blue: 0.831)
    static let accentDim = Color(red: 0.122, green: 0.361, blue: 0.337)
    static let onAccent = Color(red: 0.0, green: 0.125, blue: 0.11)
    static let text = Color(red: 0.91, green: 0.93, blue: 0.96)
    static let textDim = Color(red: 0.54, green: 0.59, blue: 0.67)
    static let live = Color(red: 1.0, green: 0.353, blue: 0.373)
    static let warning = Color(red: 1.0, green: 0.69, blue: 0.125)
}

/// Artwork with a generated fallback (initials over a gradient) for missing logos and posters.
struct Artwork: View {
    let url: String?
    let title: String
    var contentMode: ContentMode = .fill
    var fontSize: CGFloat = 28
    /// Decode size; logos (fit) are drawn small, posters and stills larger.
    var maxPixels: Int?

    var body: some View {
        ZStack {
            LinearGradient(colors: Self.colors(for: title), startPoint: .topLeading, endPoint: .bottomTrailing)
            Text(Self.initials(title))
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white.opacity(0.85))
            if let url, !url.isEmpty, let u = URL(string: url) {
                CachedImage(url: u, maxPixels: maxPixels ?? (contentMode == .fit ? 256 : 720), contentMode: contentMode)
            }
        }
        .clipped()
    }

    static func initials(_ s: String) -> String {
        let words = s.split(whereSeparator: { " -_|:".contains($0) }).filter { $0.first?.isLetter == true || $0.first?.isNumber == true }
        if words.isEmpty { return "•" }
        if words.count == 1 { return String(words[0].prefix(2)).uppercased() }
        return (String(words[0].prefix(1)) + String(words[1].prefix(1))).uppercased()
    }

    private static func colors(for seed: String) -> [Color] {
        let palettes: [[Color]] = [
            [Color(red: 0.12, green: 0.23, blue: 0.37), Color(red: 0.06, green: 0.11, blue: 0.18)],
            [Color(red: 0.23, green: 0.12, blue: 0.29), Color(red: 0.10, green: 0.06, blue: 0.14)],
            [Color(red: 0.12, green: 0.29, blue: 0.23), Color(red: 0.06, green: 0.14, blue: 0.10)],
            [Color(red: 0.29, green: 0.18, blue: 0.12), Color(red: 0.14, green: 0.08, blue: 0.06)],
            [Color(red: 0.16, green: 0.18, blue: 0.33), Color(red: 0.07, green: 0.08, blue: 0.16)],
        ]
        var h: UInt32 = 0
        for b in seed.utf8 { h = h &* 31 &+ UInt32(b) }
        return palettes[Int(h % UInt32(palettes.count))]
    }
}

struct ProgressLine: View {
    let fraction: Double
    var color: Color = Palette.accent
    var height: CGFloat = 4

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.15))
                Capsule().fill(color).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: height)
    }
}

struct Pill: View {
    let text: String
    var background: Color = Palette.accentDim
    var foreground: Color = Palette.accent

    var body: some View {
        Text(text)
            .font(.system(size: 16, weight: .bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(background, in: RoundedRectangle(cornerRadius: 5))
            .foregroundStyle(foreground)
    }
}

struct EmptyStateView: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Text(title).font(.title2.weight(.semibold))
            Text(message).foregroundStyle(Palette.textDim).multilineTextAlignment(.center)
        }
        .frame(maxWidth: 900)
        .padding(60)
    }
}

/// Flat focusable row with the app's focus treatment (used in lists and side panels).
struct RowButtonStyle: ButtonStyle {
    var selected = false
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 22)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(focused ? Palette.surfaceFocus : (selected ? Palette.surfaceHigh : .clear))
            )
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(focused ? Palette.accent : .clear, lineWidth: 3))
            .foregroundStyle(selected && !focused ? Palette.accent : Palette.text)
            .scaleEffect(focused ? 1.03 : 1)
            .animation(.easeOut(duration: 0.12), value: focused)
    }
}

struct Toast: View {
    let text: String

    var body: some View {
        Text(text)
            .padding(.horizontal, 28)
            .padding(.vertical, 14)
            .background(Palette.surfaceHigh, in: Capsule())
            .overlay(Capsule().stroke(Palette.outline))
    }
}
