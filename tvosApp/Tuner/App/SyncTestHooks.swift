#if DEBUG
import Foundation
import TunerCore

/// Debug-only hooks for `tools/e2e/icloud_sync.py`, which runs two simulators against real iCloud.
/// It may run on a real account, so everything here touches only the playlist named `name`.
/// The state file holds nothing about any other playlist, and no passwords.
enum SyncTestHooks {
    static let name = "E2E Test"
    private static let password = "secret"

    @MainActor
    static func run(_ core: Tuner) {
        let args = ProcessInfo.processInfo.arguments
        guard args.contains("-syncTest") else { return }
        func value(_ flag: String) -> String? {
            guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
            return args[i + 1]
        }
        Task {
            // Long enough for the store to report what it holds before anything is changed.
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if let server = value("-syncTestPlaylist"), await source(core) == nil {
                _ = try? await core.addSource(input: SourceInput(
                    name: name, type: .xtream, url: server, username: "showcase", password: password,
                    epgUrls: [], userAgent: nil, epgOffsetMinutes: 0, refreshHours: 24, enabled: true))
            }
            if let s = await source(core) {
                if let key = value("-syncTestFavorite") { try? await core.setFavorite(kind: .live, sourceId: s.id, key: key, favorite: true) }
                if let key = value("-syncTestUnfavorite") { try? await core.setFavorite(kind: .live, sourceId: s.id, key: key, favorite: false) }
                if let ms = value("-syncTestProgress").flatMap(Int64.init) { await saveProgress(core, s, ms) }
                if args.contains("-syncTestRemove") { try? await core.deleteSource(id: s.id) }
            }
            if args.contains("-syncTestOff") { core.setCloudSyncEnabled(enabled: false, deleteCloudCopy: false) }
            if args.contains("-syncTestOn") { core.setCloudSyncEnabled(enabled: true, deleteCloudCopy: false) }
            while true {
                await writeState(core)
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    private static func source(_ core: Tuner) async -> Source? {
        ((try? await core.sources()) ?? []).first { $0.name == name }
    }

    private static func saveProgress(_ core: Tuner, _ s: Source, _ ms: Int64) async {
        guard let groups = try? await core.vodGroups(kind: .movie, includeHidden: false) else { return }
        for g in groups where g.sourceId == s.id && g.type == .category {
            if let film = (try? await core.movies(group: g, offset: 0, limit: 1))?.first,
               let item = try? await core.playMovie(movie: film) {
                try? await core.saveProgress(request: item, positionMs: ms, durationMs: 5_400_000)
                return
            }
        }
    }

    private static func writeState(_ core: Tuner) async {
        let all = (try? await core.sources()) ?? []
        let s = all.first { $0.name == name }
        var state: [String: Any] = [
            "at": Date().timeIntervalSince1970,
            "cloudOn": core.cloudSyncEnabled,
            "otherPlaylists": all.filter { $0.name != name }.count,
            "hasE2E": s != nil,
        ]
        if let s {
            state["passwordMatches"] = s.password == password
            state["synced"] = s.lastRefreshed != nil
            if let text = try? await core.exportUserData(),
               let root = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] {
                let items = root["items"] as? [[String: Any]] ?? []
                state["favorites"] = items
                    .filter { ($0["sourceId"] as? NSNumber)?.int64Value == s.id && $0["favorite"] as? Bool == true }
                    .compactMap { $0["key"] as? String }.sorted()
                let progress = root["progress"] as? [[String: Any]] ?? []
                state["progressMs"] = progress
                    .filter { ($0["sourceId"] as? NSNumber)?.int64Value == s.id }
                    .compactMap { ($0["positionMs"] as? NSNumber)?.int64Value }.max() ?? 0
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: state),
              let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        try? data.write(to: dir.appendingPathComponent("e2e-state.json"), options: .atomic)
    }
}
#endif
