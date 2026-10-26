import SwiftUI

/// Shows a licence text bundled from the repository's `licenses/` folder.
/// Each paragraph takes focus, so swiping scrolls through it.
struct LicenseView: View {
    let file: String
    let title: String

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                Text(title).font(.largeTitle.weight(.bold)).padding(.bottom, 20)
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, p in
                    Button {} label: { Text(p).font(.callout) }
                        .buttonStyle(ParagraphStyle())
                }
            }
            .padding(.horizontal, 120)
            .padding(.vertical, 60)
        }
        .background(Palette.background.ignoresSafeArea())
    }

    private var paragraphs: [String] {
        guard let url = Bundle.main.url(forResource: file, withExtension: nil),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.components(separatedBy: "\n\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map(reflow)
    }

    /// Rejoins the hard-wrapped lines of a paragraph; list items keep their own line.
    private func reflow(_ p: String) -> String {
        p.split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> String in
                let t = line.trimmingCharacters(in: .whitespaces)
                return t.hasPrefix("- ") ? "\n" + t : " " + t
            }
            .joined()
            .replacingOccurrences(of: " {2,}", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct ParagraphStyle: ButtonStyle {
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(focused ? Palette.surface : .clear))
            .foregroundStyle(Palette.text)
    }
}
