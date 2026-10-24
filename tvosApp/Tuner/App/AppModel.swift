import Foundation
import TunerCore

/// Owns the shared Kotlin core and republishes its change and sync notifications for SwiftUI.
@MainActor
final class AppModel: ObservableObject {
    let core: Tuner
    let player = PlayerEngine()

    @Published var hasSources: Bool?
    /// Bumped whenever catalog data or preferences change; views reload on change.
    @Published var version: Int64 = 0
    @Published var syncStates: [SyncState] = []

    private var subscriptions: [Subscription] = []

    init() {
        core = Tuner(context: PlatformContext())
        // AVPlayer can't play raw MPEG-TS, so Xtream live channels use HLS on tvOS.
        core.setPlatformDefaults(preferHlsLive: true)
        subscriptions.append(core.watchChanges { [weak self] v in
            Task { @MainActor in self?.version = v.int64Value }
        })
        subscriptions.append(core.watchSync { [weak self] states in
            Task { @MainActor in self?.syncStates = states }
        })
        Task {
            #if DEBUG
            await addDemoSourceIfRequested()
            #endif
            await refreshHasSources()
        }
        core.refreshStale()
    }

    #if DEBUG
    /// UI tests pass `-demoXtream <server>` to start with the local fixture server configured.
    private func addDemoSourceIfRequested() async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-demoXtream"), i + 1 < args.count,
              (try? await core.hasSources())?.boolValue != true else { return }
        let input = SourceInput(name: "Demo", type: .xtream, url: args[i + 1], username: "demo", password: "secret",
                                epgUrls: [], userAgent: nil, epgOffsetMinutes: 0, refreshHours: 24, enabled: true)
        if let id = try? await core.addSource(input: input) {
            _ = try? await core.refreshNow(sourceId: id.int64Value)
        }
    }
    #endif

    func refreshHasSources() async {
        hasSources = (try? await core.hasSources())?.boolValue ?? false
    }

    var runningSync: SyncState? { syncStates.first { $0.running } }
}

extension Int64 {
    var date: Date { Date(timeIntervalSince1970: TimeInterval(self) / 1000) }
}

enum Fmt {
    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("EEE d MMM")
        return f
    }()

    static func time(_ ms: Int64) -> String { time.string(from: ms.date) }
    static func range(_ start: Int64, _ stop: Int64) -> String { "\(time(start)) – \(time(stop))" }
    static func day(_ ms: Int64) -> String { day.string(from: ms.date) }

    static func duration(_ ms: Int64) -> String {
        let total = ms / 1000
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func minutes(_ secs: Int64) -> String {
        let m = secs / 60
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
