import SwiftUI
import TunerCore

enum HomeTab: Hashable { case live, movies, series, search, settings }

/// Switches to the Live TV tab: Menu at the top level of another tab goes there instead of
/// leaving the app.
extension EnvironmentValues {
    @Entry var goLive: () -> Void = {}
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab: HomeTab = .live
    @State private var expiry: String?
    #if DEBUG
    /// UI tests pass `-debugPlay <url>` to open the system player on a URL directly.
    @State private var debugRequest: PlaybackRequest? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-debugPlay"), i + 1 < args.count else { return nil }
        return PlaybackRequest(url: args[i + 1], title: "Debug", subtitle: nil, image: nil, userAgent: nil, referrer: nil,
                               isLive: args.contains("-debugLive"), startPositionMs: 0, progressKey: nil, progressParentKey: nil, sourceId: 0, kind: .movie, channelId: nil)
    }()
    #endif

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            switch model.hasSources {
            case .none:
                ProgressView()
            case .some(false):
                WelcomeView()
            case .some(true):
                TabView(selection: $tab) {
                    GuideView(active: tab == .live).tabItem { Label("Live TV", systemImage: "tv") }.tag(HomeTab.live)
                    VodBrowserView(kind: .movie).tabItem { Label("Movies", systemImage: "film") }.tag(HomeTab.movies)
                    VodBrowserView(kind: .series).tabItem { Label("Series", systemImage: "play.rectangle.on.rectangle") }.tag(HomeTab.series)
                    SearchView().tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(HomeTab.search)
                    SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }.tag(HomeTab.settings)
                }
                .environment(\.goLive) { tab = .live }
                .overlay(alignment: .topTrailing) { SyncBadge() }
                .overlay(alignment: .bottom) { if let expiry { Toast(text: expiry).padding(.bottom, 50) } }
                .task {
                    // Once per launch: remind about a subscription that is about to end.
                    guard !model.checkedExpiry else { return }
                    model.checkedExpiry = true
                    guard let s = (try? await model.core.expiringSources(days: expiryWarningDays))?.first, let text = expiryText(s) else { return }
                    expiry = "Your subscription \"\(s.name)\" \(text)"
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                    expiry = nil
                }
            }
        }
        #if DEBUG
        .fullScreenCover(item: Binding(get: { debugRequest.map(IdentifiedRequest.init) }, set: { debugRequest = $0?.request })) {
            VodPlayerView(request: $0.request)
        }
        #endif
    }
}

private struct SyncBadge: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let s = model.runningSync {
            Text("Updating · \(s.phase)\(s.count > 0 ? " \(s.count.formatted())" : "")")
                .font(.caption)
                .padding(.horizontal, 18).padding(.vertical, 8)
                .background(Palette.surfaceHigh, in: Capsule())
                .foregroundStyle(Palette.textDim)
                .padding(30)
        }
    }
}

struct WelcomeView: View {
    @State private var adding = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.background, Palette.surface, Palette.accentDim.opacity(0.35)], startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
            VStack(alignment: .leading, spacing: 20) {
                Text("IPTV Enhanced").font(.system(size: 96, weight: .bold)).foregroundStyle(Palette.accent)
                Text("A free, open-source TV guide and player for the playlists you already have.").font(.title2.weight(.semibold))
                Text("Works with M3U playlists, XMLTV guides and Xtream logins. Your data stays on this device.")
                    .foregroundStyle(Palette.textDim)
                Text("IPTV Enhanced provides no channels or content. Only add playlists you are authorised to use.")
                    .foregroundStyle(Palette.textDim)
                Button("Add your playlist") { adding = true }
                    .padding(.top, 30)
            }
            .padding(120)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fullScreenCover(isPresented: $adding) { SourceEditView(sourceId: nil) }
    }
}
