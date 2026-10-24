import SwiftUI
import TunerCore

enum VodItem: Identifiable, Hashable {
    case movie(Movie)
    case series(Series)
    case resume(WatchProgress)

    var id: String {
        switch self {
        case let .movie(m): return "m\(m.id)"
        case let .series(s): return "s\(s.id)"
        case let .resume(p): return "p\(p.key)"
        }
    }

    var title: String {
        switch self {
        case let .movie(m): return m.name
        case let .series(s): return s.name
        case let .resume(p): return p.title
        }
    }

    var image: String? {
        switch self {
        case let .movie(m): return m.poster
        case let .series(s): return s.cover
        case let .resume(p): return p.image
        }
    }
}

@MainActor
final class VodModel: ObservableObject {
    let kind: ContentKind
    @Published var groups: [ChannelGroup] = []
    @Published var group: ChannelGroup?
    @Published var items: [VodItem] = []
    @Published var loaded = false
    private var endReached = true
    private var loadingMore = false
    private static let page: Int32 = 200
    weak var app: AppModel?

    init(kind: ContentKind) { self.kind = kind }

    private var continueGroup: ChannelGroup {
        ChannelGroup(id: "continue", type: .recents, kind: kind, sourceId: 0, key: "", name: "Continue watching", sourceName: nil, hidden: false, locked: false)
    }

    func reload() async {
        guard let core = app?.core else { return }
        let base = (try? await core.vodGroups(kind: kind, includeHidden: false)) ?? []
        let resume = await resumeItems()
        groups = (resume.isEmpty ? [] : [continueGroup]) + base
        let next = groups.first { $0.id == group?.id } ?? groups.first { $0.type == .category } ?? groups.first
        await load(next)
    }

    func select(_ g: ChannelGroup) async { await load(g) }

    private func resumeItems() async -> [VodItem] {
        guard let core = app?.core else { return [] }
        let list = (try? await core.continueWatching(limit: 30)) ?? []
        return list.filter { kind == .movie ? $0.kind == .movie : $0.kind == .series }.map { VodItem.resume($0) }
    }

    private func load(_ g: ChannelGroup?) async {
        group = g
        endReached = true
        let page = await fetch(g, offset: 0)
        guard group?.id == g?.id else { return }
        items = dedupe(page)
        endReached = page.count < Self.page
        loaded = true
    }

    func loadMoreIfNeeded(_ item: VodItem) {
        guard !endReached, !loadingMore, items.suffix(24).contains(where: { $0.id == item.id }) else { return }
        loadingMore = true
        let g = group
        Task {
            let more = await fetch(g, offset: Int32(items.count))
            if group?.id == g?.id {
                if more.count < Self.page { endReached = true }
                items = dedupe(items + more)
            }
            loadingMore = false
        }
    }

    private func dedupe(_ list: [VodItem]) -> [VodItem] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.id).inserted }
    }

    private func fetch(_ g: ChannelGroup?, offset: Int32) async -> [VodItem] {
        guard let g, let core = app?.core else { return [] }
        if g.id == "continue" { return offset > 0 ? [] : await resumeItems() }
        if kind == .movie {
            return ((try? await core.movies(group: g, offset: offset, limit: Self.page)) ?? []).map { .movie($0) }
        }
        return ((try? await core.series(group: g, offset: offset, limit: Self.page)) ?? []).map { .series($0) }
    }
}

struct VodBrowserView: View {
    let kind: ContentKind

    @EnvironmentObject private var app: AppModel
    @StateObject private var model: VodModel
    @StateObject private var gate = PinGate()
    @State private var path = NavigationPath()
    @State private var resumeRequest: PlaybackRequest?

    init(kind: ContentKind) {
        self.kind = kind
        _model = StateObject(wrappedValue: VodModel(kind: kind))
    }

    var body: some View {
        NavigationStack(path: $path) {
            HStack(alignment: .top, spacing: 30) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(kind == .movie ? "Movies" : "Series").font(.title2.weight(.bold)).padding(.leading, 22).padding(.bottom, 12)
                    ScrollView(showsIndicators: false) {
                        VStack(spacing: 6) {
                            ForEach(model.groups, id: \.id) { g in
                                Button {
                                    gate.run(app.core, locked: g.locked, title: g.name) { Task { await model.select(g) } }
                                } label: {
                                    Text((g.type == .favorites ? "★  " : "") + g.name).lineLimit(1)
                                }
                                .buttonStyle(RowButtonStyle(selected: g.id == model.group?.id))
                            }
                        }
                        .padding(.horizontal, 10)
                    }
                }
                .frame(width: 440)
                .focusSection()

                if model.loaded && model.groups.isEmpty {
                    EmptyStateView(title: kind == .movie ? "No movies" : "No series",
                                   message: "Your playlists don't include any. Xtream accounts usually do.")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if model.loaded && model.items.isEmpty {
                    EmptyStateView(title: "Nothing here",
                                   message: model.group?.type == .favorites ? "Press and hold on a poster to add it to Favorites." : "This category is empty.")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 230), spacing: 40)], spacing: 50) {
                            ForEach(model.items) { item in
                                Button { open(item) } label: { PosterCard(item: item) }
                                    .buttonStyle(.borderless)
                                    .contextMenu { menu(item) }
                                    .onAppear { model.loadMoreIfNeeded(item) }
                            }
                        }
                        .padding(40)
                    }
                    .focusSection()
                }
            }
            .navigationDestination(for: Movie.self) { MovieDetailView(movie: $0) }
            .navigationDestination(for: Series.self) { SeriesDetailView(series: $0) }
        }
        .task {
            model.app = app
            if !model.loaded { await model.reload() }
        }
        .onChange(of: app.version) { _, _ in Task { await model.reload() } }
        .fullScreenCover(item: Binding(get: { resumeRequest.map(IdentifiedRequest.init) }, set: { resumeRequest = $0?.request })) {
            VodPlayerView(request: $0.request)
        }
        .pinGate(gate)
    }

    private func open(_ item: VodItem) {
        switch item {
        case let .movie(m): path.append(m)
        case let .series(s): path.append(s)
        case let .resume(p): resume(p)
        }
    }

    private func resume(_ p: WatchProgress) {
        Task {
            do {
                resumeRequest = try await app.core.playProgress(progress: p)
            } catch {
                gate.pending = (p.title, { resume(p) })
            }
        }
    }

    @ViewBuilder
    private func menu(_ item: VodItem) -> some View {
        switch item {
        case let .movie(m):
            Button(m.favorite ? "Remove from Favorites" : "Add to Favorites") {
                Task { try? await app.core.setFavorite(kind: .movie, sourceId: m.sourceId, key: m.key, favorite: !m.favorite) }
            }
        case let .series(s):
            Button(s.favorite ? "Remove from Favorites" : "Add to Favorites") {
                Task { try? await app.core.setFavorite(kind: .series, sourceId: s.sourceId, key: s.key, favorite: !s.favorite) }
            }
        case let .resume(p):
            Button("Remove from Continue watching") { Task { try? await app.core.clearProgress(key: p.key) } }
        }
    }
}

struct PosterCard: View {
    let item: VodItem
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack(alignment: .bottom) {
                Artwork(url: item.image, title: item.title, fontSize: 40)
                if case let .resume(p) = item {
                    ProgressLine(fraction: Double(p.fraction)).padding(12)
                }
                if isFavorite {
                    Text("★").foregroundStyle(Palette.warning).padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
            .frame(width: 230, height: 345)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(focused ? Palette.accent : .clear, lineWidth: 4))
            .scaleEffect(focused ? 1.08 : 1)
            .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: 20, y: 10)
            .animation(.easeOut(duration: 0.15), value: focused)
            Text(item.title).font(.callout.weight(.medium)).lineLimit(2).frame(width: 230, alignment: .leading)
            Text(subtitle).font(.caption).foregroundStyle(Palette.textDim).lineLimit(1)
        }
    }

    private var isFavorite: Bool {
        switch item {
        case let .movie(m): return m.favorite
        case let .series(s): return s.favorite
        case .resume: return false
        }
    }

    private var subtitle: String {
        switch item {
        case let .movie(m): return [m.year, m.rating.map { String(format: "★ %.1f", $0.doubleValue) }].compactMap { $0 }.joined(separator: "  ")
        case let .series(s): return [s.year, s.genre].compactMap { $0 }.joined(separator: "  ·  ")
        case let .resume(p): return p.subtitle ?? ""
        }
    }
}

struct MovieDetailView: View {
    let movie: Movie

    @EnvironmentObject private var app: AppModel
    @StateObject private var gate = PinGate()
    @State private var details: MovieDetails?
    @State private var request: PlaybackRequest?
    @State private var favorite = false
    @State private var message: String?

    var body: some View {
        ZStack(alignment: .topLeading) {
            Backdrop(url: details?.backdrop ?? movie.poster)
            HStack(alignment: .top, spacing: 60) {
                Artwork(url: movie.poster, title: movie.name, fontSize: 60)
                    .frame(width: 360, height: 540).clipShape(RoundedRectangle(cornerRadius: 18))
                VStack(alignment: .leading, spacing: 18) {
                    Text(movie.name).font(.largeTitle.weight(.bold)).lineLimit(2)
                    Text([movie.year, details?.genre, details?.durationSecs.map { Fmt.minutes($0.int64Value) },
                          movie.rating.map { String(format: "★ %.1f", $0.doubleValue) }].compactMap { $0 }.joined(separator: "  ·  "))
                        .foregroundStyle(Palette.textDim)
                    Text(details?.plot ?? (details == nil ? "" : "No description available.")).lineLimit(6)
                    if let d = details?.director { Text("Director: \(d)").font(.callout).foregroundStyle(Palette.textDim) }
                    if let c = details?.cast { Text("Cast: \(c)").font(.callout).foregroundStyle(Palette.textDim).lineLimit(2) }
                    if let p = details?.progress, !p.watched {
                        VStack(alignment: .leading, spacing: 6) {
                            ProgressLine(fraction: Double(p.fraction)).frame(width: 420)
                            Text("\(Fmt.duration(p.positionMs)) of \(Fmt.duration(p.durationMs))").font(.caption).foregroundStyle(Palette.textDim)
                        }
                    }
                    HStack(spacing: 30) {
                        let resumable = details?.progress.map { !$0.watched } ?? false
                        Button(resumable ? "Resume" : "Play") { play(fromStart: false) }
                        if resumable { Button("Play from start") { play(fromStart: true) } }
                        Button(favorite ? "★ Favorite" : "☆ Favorite") {
                            Task {
                                try? await app.core.setFavorite(kind: .movie, sourceId: movie.sourceId, key: movie.key, favorite: !favorite)
                                favorite.toggle()
                            }
                        }
                    }
                    .padding(.top, 10)
                    if let message { Text(message).foregroundStyle(Palette.live) }
                }
            }
            .padding(80)
        }
        .task {
            favorite = movie.favorite
            details = try? await app.core.movieDetails(movie: movie)
        }
        .fullScreenCover(item: Binding(get: { request.map(IdentifiedRequest.init) }, set: { request = $0?.request })) {
            VodPlayerView(request: $0.request)
        }
        .onChange(of: request == nil) { _, closed in
            if closed { Task { details = try? await app.core.movieDetails(movie: movie) } }
        }
        .pinGate(gate)
    }

    private func play(fromStart: Bool) {
        gate.run(app.core, locked: movie.locked, title: movie.name) {
            Task {
                do {
                    let r = try await app.core.playMovie(movie: movie)
                    request = fromStart ? r.doCopy(url: r.url, title: r.title, subtitle: r.subtitle, image: r.image, userAgent: r.userAgent, referrer: r.referrer, isLive: r.isLive, startPositionMs: 0, progressKey: r.progressKey, progressParentKey: r.progressParentKey, sourceId: r.sourceId, kind: r.kind, channelId: r.channelId) : r
                } catch {
                    message = error.localizedDescription
                }
            }
        }
    }
}

struct SeriesDetailView: View {
    let series: Series

    @EnvironmentObject private var app: AppModel
    @StateObject private var gate = PinGate()
    @State private var details: SeriesDetails?
    @State private var error: String?
    @State private var season: Int32?
    @State private var request: PlaybackRequest?
    @State private var favorite = false

    var body: some View {
        ZStack(alignment: .topLeading) {
            Backdrop(url: details?.backdrop ?? series.cover)
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    HStack(alignment: .top, spacing: 40) {
                        Artwork(url: series.cover, title: series.name, fontSize: 40)
                            .frame(width: 200, height: 300).clipShape(RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 14) {
                            Text(series.name).font(.largeTitle.weight(.bold)).lineLimit(1)
                            Text([series.year, details?.genre, details.map { "\($0.seasons.count) season\($0.seasons.count == 1 ? "" : "s")" }]
                                .compactMap { $0 }.joined(separator: "  ·  ")).foregroundStyle(Palette.textDim)
                            Text(details?.plot ?? series.plot ?? "").font(.callout).foregroundStyle(Palette.textDim).lineLimit(4)
                            Button(favorite ? "★ Favorite" : "☆ Favorite") {
                                Task {
                                    try? await app.core.setFavorite(kind: .series, sourceId: series.sourceId, key: series.key, favorite: !favorite)
                                    favorite.toggle()
                                }
                            }
                        }
                    }
                    if let error { Text(error).foregroundStyle(Palette.live) }
                    if let d = details {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 20) {
                                ForEach(d.seasons.map { $0.int32Value }, id: \.self) { s in
                                    Button("Season \(s)") { season = s }
                                        .buttonStyle(RowButtonStyle(selected: s == season))
                                        .fixedSize()
                                }
                            }
                            .padding(.vertical, 10)
                        }
                        .focusSection()
                        LazyVStack(spacing: 16) {
                            ForEach(d.episodes.filter { $0.season == season }, id: \.remoteId) { e in
                                Button { play(e) } label: { EpisodeRow(episode: e) }
                                    .buttonStyle(RowButtonStyle())
                            }
                        }
                        .focusSection()
                    } else if error == nil {
                        ProgressView()
                    }
                }
                .padding(80)
            }
        }
        .task { await load() }
        .fullScreenCover(item: Binding(get: { request.map(IdentifiedRequest.init) }, set: { request = $0?.request })) {
            VodPlayerView(request: $0.request)
        }
        .onChange(of: request == nil) { _, closed in if closed { Task { await load() } } }
        .pinGate(gate)
    }

    private func load() async {
        favorite = series.favorite
        do {
            let d = try await app.core.seriesDetails(series: series)
            details = d
            if season == nil {
                let watched = d.episodes.filter { $0.progress != nil }.max { $0.progress!.updatedAt < $1.progress!.updatedAt }
                season = watched?.season ?? d.seasons.first?.int32Value
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func play(_ e: Episode) {
        gate.run(app.core, locked: series.locked, title: series.name) {
            Task { request = try? await app.core.playEpisode(series: series, episode: e) }
        }
    }
}

private struct EpisodeRow: View {
    let episode: Episode

    var body: some View {
        HStack(spacing: 30) {
            ZStack(alignment: .bottom) {
                Artwork(url: episode.image, title: episode.title, fontSize: 22)
                if let p = episode.progress { ProgressLine(fraction: Double(p.fraction)).padding(8) }
            }
            .frame(width: 240, height: 135).clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 6) {
                Text("\(episode.episode). \(episode.title)").font(.headline).lineLimit(1)
                Text([episode.durationSecs.map { Fmt.minutes($0.int64Value) }, episode.progress?.watched == true ? "Watched" : nil]
                    .compactMap { $0 }.joined(separator: "  ·  ")).font(.caption).foregroundStyle(Palette.textDim)
                if let plot = episode.plot { Text(plot).font(.caption).foregroundStyle(Palette.textDim).lineLimit(2) }
            }
            Spacer()
            if episode.progress?.watched == true { Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.accent) }
        }
    }
}

struct Backdrop: View {
    let url: String?

    var body: some View {
        ZStack {
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { $0.image?.resizable().aspectRatio(contentMode: .fill).opacity(0.35) }
            }
            LinearGradient(colors: [Palette.background, Palette.background.opacity(0.85), .clear], startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, Palette.background], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }
}
