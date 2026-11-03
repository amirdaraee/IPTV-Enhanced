import SwiftUI
import TunerCore

struct SearchView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @StateObject private var gate = PinGate()
    @State private var query = ""
    @State private var results: SearchResults?
    @State private var fullscreen = false
    @State private var message: String?
    @State private var recent: [String] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 40.ui) {
                    if let r = results {
                        if r.isEmpty {
                            EmptyStateView(title: "No results", message: "Nothing matches \"\(query)\".")
                        }
                        if !r.channels.isEmpty {
                            section("Channels") {
                                Button { addAllToFavorites(r.channels) } label: {
                                    Label("Add all to Favorites", systemImage: "star.fill")
                                        .frame(width: 360.ui, height: 48.ui, alignment: .leading)
                                }
                                ForEach(r.channels, id: \.id) { ch in
                                    Button { play(ch, lineup: r.channels) } label: {
                                        HStack(spacing: 16.ui) {
                                            Artwork(url: ch.logo, title: ch.name, contentMode: .fit, fontSize: 14.ui)
                                                .frame(width: 70.ui, height: 48.ui).clipShape(RoundedRectangle(cornerRadius: 6.ui))
                                            Text(ch.name).lineLimit(2)
                                        }
                                        .frame(width: 360.ui, alignment: .leading)
                                    }
                                }
                            }
                        }
                        if !r.programmes.isEmpty {
                            section("On TV") {
                                ForEach(Array(r.programmes.enumerated()), id: \.offset) { _, hit in
                                    Button { play(hit.channel, lineup: [hit.channel]) } label: {
                                        VStack(alignment: .leading, spacing: 4.ui) {
                                            Text(hit.programme.title).lineLimit(1)
                                            Text(Fmt.range(hit.programme.start, hit.programme.stop)).font(.caption).foregroundStyle(.secondary)
                                            Text(hit.channel.name).font(.caption).foregroundStyle(.secondary)
                                        }
                                        .frame(width: 400.ui, alignment: .leading)
                                    }
                                }
                            }
                        }
                        if !r.movies.isEmpty {
                            section("Movies") {
                                ForEach(r.movies, id: \.id) { m in
                                    NavigationLink(value: m) { PosterCard(item: .movie(m)) }.buttonStyle(.borderless)
                                }
                            }
                        }
                        if !r.series.isEmpty {
                            section("Series") {
                                ForEach(r.series, id: \.id) { s in
                                    NavigationLink(value: s) { PosterCard(item: .series(s)) }.buttonStyle(.borderless)
                                }
                            }
                        }
                    } else {
                        if query.isEmpty && !recent.isEmpty {
                            section("Recent searches") {
                                ForEach(recent, id: \.self) { q in
                                    Button(q) { query = q }
                                }
                                Button("Clear") {
                                    app.core.clearRecentSearches()
                                    recent = []
                                }
                            }
                        }
                        Text("Search finds channels, movies, series and programmes on air in the next 24 hours.")
                            .foregroundStyle(Palette.textDim)
                    }
                }
                .padding(60.ui)
            }
            .navigationDestination(for: Movie.self) { MovieDetailView(movie: $0).onAppear(perform: saveQuery) }
            .navigationDestination(for: Series.self) { SeriesDetailView(series: $0).onAppear(perform: saveQuery) }
            #if os(iOS)
            // Always visible: there is nothing else on this screen until something is typed.
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Channels, programmes, movies, series")
            #endif
        }
        #if os(tvOS)
        .searchable(text: $query, prompt: "Channels, programmes, movies, series")
        #endif
        .onAppear { recent = app.core.recentSearches() }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            results = query.count < 2 ? nil : try? await app.core.search(query: query)
        }
        .fullScreenCover(isPresented: $fullscreen) { LivePlayerView(lineup: LiveLineup.channels) }
        .pinGate(gate)
        .overlay(alignment: .bottom) { if let message { Toast(text: message).padding(.bottom, 40.ui) } }
        .task(id: message) {
            guard message != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            message = nil
        }
    }

    private func addAllToFavorites(_ channels: [Channel]) {
        Task {
            let added = (try? await app.core.setFavorites(channels: channels, favorite: true))?.intValue ?? 0
            message = added == 0 ? "Already in Favorites" : "Added \(added) channel\(added == 1 ? "" : "s") to Favorites"
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 20.ui) {
            Text(title).font(.title3.weight(.semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 30.ui) { content() }.padding(.vertical, 20.ui)
            }
        }
    }

    /// A result was opened: keep the query for next time.
    private func saveQuery() {
        app.core.rememberSearch(query: query)
        recent = app.core.recentSearches()
    }

    private func play(_ ch: Channel, lineup: [Channel]) {
        saveQuery()
        gate.run(app.core, locked: ch.locked, title: ch.name) {
            Task {
                guard let req = try? await app.core.playChannel(channel: ch) else { return }
                engine.play(req)
                LiveLineup.start(lineup, current: ch)
                fullscreen = true
            }
        }
    }
}
