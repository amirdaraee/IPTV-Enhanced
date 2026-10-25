import Foundation
import SwiftUI
import TunerCore

/// The time of day, redrawn on the minute.
struct ClockText: View {
    var body: some View {
        TimelineView(.everyMinute) { ctx in
            Text(Fmt.time(Int64(ctx.date.timeIntervalSince1970 * 1000)))
        }
    }
}

/// Languages offered for the audio and subtitle preferences (ISO 639-1 code, name).
enum Languages {
    static let all: [(code: String, name: String)] = [
        ("en", "English"), ("fa", "Persian"), ("ar", "Arabic"), ("tr", "Turkish"), ("fr", "French"),
        ("de", "German"), ("es", "Spanish"), ("it", "Italian"), ("ru", "Russian"), ("hi", "Hindi"),
    ]

    static func name(_ code: String, empty: String) -> String {
        code.isEmpty ? empty : all.first { $0.code == code }?.name ?? code
    }

    /// The next value in "" + `all`, for settings rows that cycle on select.
    static func next(_ code: String) -> String {
        let codes = [""] + all.map(\.code)
        return codes[((codes.firstIndex(of: code) ?? -1) + 1) % codes.count]
    }
}

enum SleepTimer {
    static let minutes = [15, 30, 60, 90, 120]

    /// "Off" or the time left.
    static func label(_ at: Date?) -> String {
        guard let at else { return "Off" }
        return "\(max(1, Int(at.timeIntervalSinceNow / 60))) min left"
    }
}

/// Accounts ending within this many days get a reminder at launch and a highlighted status in Settings.
let expiryWarningDays: Int32 = 7

/// "expires in 3 days", "expires today" or "expired".
func expiryText(_ source: Source) -> String? {
    guard let at = source.expiresAt?.int64Value else { return nil }
    let left = at - Fmt.nowMs()
    if left <= 0 { return "expired" }
    switch left / Tuner.companion.DAY {
    case 0: return "expires today"
    case 1: return "expires tomorrow"
    case let d: return "expires in \(d) days"
    }
}

func isExpiringSoon(_ source: Source) -> Bool {
    guard let at = source.expiresAt?.int64Value else { return false }
    return at - Fmt.nowMs() < Int64(expiryWarningDays) * Tuner.companion.DAY
}
