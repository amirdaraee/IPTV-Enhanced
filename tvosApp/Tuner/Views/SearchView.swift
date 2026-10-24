import SwiftUI
import TunerCore

struct SearchView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @StateObject private var gate = PinGate()
    @State private var query = ""
    @State private var results: SearchResults?
    @State private var fullscreen = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 40) {
                    if let r = results {
                        if r.isEmpty {
                            EmptyStateView(title: "No results", message: "Nothing matches \"\(query)\".")
                        }
                        if !r.channels.isEmpty {
                            section("Channels") {
                                ForEach(r.channels, id: \.id) { ch in
                                    Button { play(ch, lineup: r.channels) } label: {
                                        HStack(spacing: 16) {
                                            Artwork(url: ch.logo, title: ch.name, contentMode: .fit, fontSize: 14)
                                                .frame(width: 70, height: 48).clipShape(RoundedRectangle(cornerRadius: 6))
                                            Text(ch.name).lineLimit(2)
                                        }
                                        .frame(width: 360, alignment: .leading)
                                    }
                                }
                            }
                        }
                        if !r.programmes.isEmpty {
                            section("On TV") {
                                ForEach(Array(r.programmes.enumerated()), id: \.offset) { _, hit in
                                    Button { play(hit.channel, lineup: [hit.channel]) } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(hit.programme.title).lineLimit(1)
                                            Text(Fmt.range(hit.programme.start, hit.programme.stop)).font(.caption).foregroundStyle(Palette.textDim)
                                            Text(hit.channel.name).font(.caption).foregroundStyle(Palette.textDim)
                                        }
                                        .frame(width: 400, alignment: .leading)
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
                        Text("Search finds channels, movies, series and programmes on air in the next 24 hours.")
                            .foregroundStyle(Palette.textDim)
                    }
                }
                .padding(60)
            }
            .navigationDestination(for: Movie.self) { MovieDetailView(movie: $0) }
            .navigationDestination(for: Series.self) { SeriesDetailView(series: $0) }
        }
        .searchable(text: $query, prompt: "Channels, programmes, movies, series")
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            results = query.count < 2 ? nil : try? await app.core.search(query: query)
        }
        .fullScreenCover(isPresented: $fullscreen) { LivePlayerView(lineup: LiveLineup.channels) }
        .pinGate(gate)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(title).font(.title3.weight(.semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 30) { content() }.padding(.vertical, 20)
            }
        }
    }

    private func play(_ ch: Channel, lineup: [Channel]) {
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
