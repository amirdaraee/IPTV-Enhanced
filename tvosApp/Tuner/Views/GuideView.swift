import SwiftUI
import TunerCore

private let hour: Int64 = 3_600_000
private let slot: Int64 = 1_800_000
private let viewportMs: Int64 = 150 * 60_000
private let channelWidth: CGFloat = 330
private let rowHeight: CGFloat = 86

private func align(_ t: Int64) -> Int64 { t - t % slot }

@MainActor
final class GuideModel: ObservableObject {
    @Published var groups: [ChannelGroup] = []
    @Published var group: ChannelGroup?
    @Published var channels: [Channel] = []
    @Published var programmes: [Int64: [Programme]] = [:]
    @Published var windowStart: Int64 = align(Fmt.nowMs())
    @Published var focusedChannel: Channel?
    @Published var focusedProgramme: Programme?
    @Published var loaded = false

    private var loadedFrom: Int64 = 0
    private var loadedTo: Int64 = 0
    private var requested = Set<Int64>()
    private var pendingRows = Set<Int64>()
    private var batchTask: Task<Void, Never>?
    weak var app: AppModel?

    func reload() async {
        guard let core = app?.core else { return }
        let all = (try? await core.liveGroups(includeHidden: false)) ?? []
        var visible: [ChannelGroup] = []
        for g in all {
            if g.type == .category || g.type == .all {
                visible.append(g)
            } else if let list = try? await core.channels(group: g, includeHidden: false), !list.isEmpty {
                visible.append(g)
            }
        }
        groups = visible
        let saved = UserDefaults.standard.string(forKey: "guide.group")
        let keep = group?.id ?? saved
        let next = visible.first { $0.id == keep }
            ?? visible.first { $0.type == .favorites }
            ?? visible.first { $0.type == .category }
            ?? visible.first
        await load(next, reset: next?.id != group?.id)
    }

    func select(_ g: ChannelGroup) async {
        UserDefaults.standard.set(g.id, forKey: "guide.group")
        await load(g, reset: true)
    }

    private func load(_ g: ChannelGroup?, reset: Bool) async {
        guard let core = app?.core else { return }
        group = g
        let list = g == nil ? [] : ((try? await core.channels(group: g!, includeHidden: false)) ?? [])
        let same = list.map(\.id) == channels.map(\.id)
        channels = list
        if same {
            await refreshLoaded()
        } else {
            programmes = [:]
            requested = []
        }
        if reset {
            windowStart = align(Fmt.nowMs())
        }
        loaded = true
    }

    private func refreshLoaded() async {
        guard let core = app?.core else { return }
        let rows = channels.filter { requested.contains($0.id) }
        guard !rows.isEmpty else { return }
        let fresh = (try? await core.guide(channels: rows, from: loadedFrom, to: loadedTo)) ?? []
        for r in fresh { programmes[r.channel.id] = r.programmes }
    }

    /// Called as rows appear; batches the queries for rows scrolled into view.
    func ensureLoaded(_ channel: Channel) {
        let from = windowStart - 6 * hour, to = windowStart + 12 * hour
        if from < loadedFrom || to > loadedTo {
            programmes = [:]
            requested = []
            loadedFrom = windowStart - 12 * hour
            loadedTo = windowStart + 24 * hour
        }
        guard !requested.contains(channel.id) else { return }
        requested.insert(channel.id)
        pendingRows.insert(channel.id)
        batchTask?.cancel()
        batchTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 30_000_000)
            guard !Task.isCancelled, let core = app?.core else { return }
            let ids = pendingRows
            pendingRows = []
            let rows = channels.filter { ids.contains($0.id) }
            let f = loadedFrom, t = loadedTo
            let fresh = (try? await core.guide(channels: rows, from: f, to: t)) ?? []
            if f == loadedFrom { for r in fresh { programmes[r.channel.id] = r.programmes } }
        }
    }

    func shiftWindow(by delta: Int64) {
        let minStart = align(Fmt.nowMs() - Int64(app?.core.settings.guidePastDays ?? 2) * 24 * hour)
        let maxStart = Fmt.nowMs() + Int64(app?.core.settings.guideFutureDays ?? 7) * 24 * hour
        windowStart = min(max(windowStart + delta, minStart), maxStart)
        for c in channels where requested.contains(c.id) { requested.remove(c.id); ensureLoaded(c) }
    }

    var canGoBack: Bool {
        let minStart = align(Fmt.nowMs() - Int64(app?.core.settings.guidePastDays ?? 2) * 24 * hour)
        return windowStart > minStart
    }

    /// Visible segments for a row: programmes clipped to the window, with gaps between them.
    func segments(for channel: Channel) -> [GuideSegment] {
        let windowEnd = windowStart + viewportMs
        guard let list = programmes[channel.id] else { return [.gap(start: windowStart, width: viewportMs, label: "")] }
        var out: [GuideSegment] = []
        var cursor = windowStart
        for p in list where p.stop > windowStart && p.start < windowEnd {
            let s = max(p.start, windowStart), e = min(p.stop, windowEnd)
            if s > cursor { out.append(.gap(start: cursor, width: s - cursor, label: "")) }
            out.append(.programme(p, width: e - s))
            cursor = e
        }
        if out.isEmpty { return [.gap(start: windowStart, width: viewportMs, label: "No information")] }
        if cursor < windowEnd { out.append(.gap(start: cursor, width: windowEnd - cursor, label: "")) }
        return out
    }
}

enum GuideSegment: Identifiable {
    case programme(Programme, width: Int64)
    case gap(start: Int64, width: Int64, label: String)

    var id: Int64 {
        switch self {
        case let .programme(p, _): return p.start
        case let .gap(start, _, _): return -start
        }
    }
}

enum GuideFocus: Hashable {
    case cell(Int64, Int64)     // channel id, segment id
    case left(Int64)
    case right(Int64)
    case group(String)
}

struct GuideView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @StateObject private var model = GuideModel()
    @StateObject private var gate = PinGate()
    @FocusState private var focus: GuideFocus?
    @State private var showGroups = false
    @State private var fullscreen = false
    @State private var catchup: PlaybackRequest?
    @State private var message: String?
    @State private var now = Fmt.nowMs()
    @State private var lastCellTime: Int64 = Fmt.nowMs()

    private let ticker = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            let msPerPoint = Double(viewportMs) / Double(geo.size.width - channelWidth - 16)
            ZStack(alignment: .leading) {
                VStack(spacing: 0) {
                    GuideHeader(model: model, now: now)
                    ruler(msPerPoint: msPerPoint)
                    if model.loaded && model.channels.isEmpty {
                        EmptyStateView(
                            title: model.group?.type == .favorites ? "No favorites yet" : "No channels here",
                            message: "Press Menu to choose another group, or press and hold on a programme to add its channel to Favorites."
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .focusable()
                    } else {
                        grid(msPerPoint: msPerPoint)
                    }
                }
                if showGroups { groupsPanel }
            }
        }
        .background(Palette.background)
        .overlay(alignment: .bottom) { if let message { Toast(text: message).padding(.bottom, 40) } }
        .onExitCommand {
            if showGroups { closeGroups() } else { openGroups() }
        }
        .onChange(of: focus) { _, f in handleFocus(f) }
        .onReceive(ticker) { _ in now = Fmt.nowMs() }
        .task {
            model.app = app
            if !model.loaded { await model.reload() }
        }
        .onChange(of: app.version) { _, _ in Task { await model.reload() } }
        .task(id: message) {
            guard message != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            message = nil
        }
        .fullScreenCover(isPresented: $fullscreen) { LivePlayerView(lineup: model.channels) }
        .fullScreenCover(item: Binding(get: { catchup.map(IdentifiedRequest.init) }, set: { catchup = $0?.request })) { r in
            VodPlayerView(request: r.request)
        }
        .pinGate(gate)
    }

    // MARK: grid

    private func ruler(msPerPoint: Double) -> some View {
        ZStack(alignment: .leading) {
            Text(Fmt.day(model.windowStart)).font(.caption).foregroundStyle(Palette.textDim).padding(.leading, 40)
            ForEach(0..<Int(viewportMs / slot), id: \.self) { i in
                let t = model.windowStart + Int64(i) * slot
                HStack(spacing: 8) {
                    Rectangle().fill(Palette.outline).frame(width: 2, height: 18)
                    Text(Fmt.time(t)).font(.caption).foregroundStyle(now >= t && now < t + slot ? Palette.accent : Palette.textDim)
                }
                .offset(x: channelWidth + 8 + CGFloat(Double(t - model.windowStart) / msPerPoint))
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, maxHeight: 44, alignment: .leading)
    }

    private func grid(msPerPoint: Double) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(model.channels, id: \.id) { ch in
                        row(ch, msPerPoint: msPerPoint)
                            .id(ch.id)
                            .onAppear { model.ensureLoaded(ch) }
                    }
                }
                .padding(.vertical, 12)
            }
            .overlay(alignment: .topLeading) { nowLine(msPerPoint: msPerPoint) }
            .onChange(of: model.group?.id) { _, _ in
                if let first = model.channels.first { proxy.scrollTo(first.id, anchor: .top) }
            }
        }
    }

    @ViewBuilder
    private func nowLine(msPerPoint: Double) -> some View {
        if now >= model.windowStart && now < model.windowStart + viewportMs {
            Rectangle().fill(Palette.live.opacity(0.85)).frame(width: 3)
                .offset(x: channelWidth + 8 + CGFloat(Double(now - model.windowStart) / msPerPoint))
                .allowsHitTesting(false)
        }
    }

    private func row(_ ch: Channel, msPerPoint: Double) -> some View {
        let playing = engine.current?.channelId?.int64Value == ch.id
        let focusedRow = model.focusedChannel?.id == ch.id
        return HStack(spacing: 0) {
            HStack(spacing: 14) {
                Text(ch.number.map { "\($0)" } ?? "").font(.callout).foregroundStyle(playing ? Palette.accent : Palette.textDim).frame(width: 56, alignment: .leading)
                Artwork(url: ch.logo, title: ch.name, contentMode: .fit, fontSize: 16)
                    .frame(width: 70, height: 50).clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 2) {
                    Text(ch.name).font(.callout.weight(.medium)).lineLimit(1).foregroundStyle(playing ? Palette.accent : Palette.text)
                    HStack(spacing: 6) {
                        if ch.favorite { Text("★").foregroundStyle(Palette.warning) }
                        if ch.hasCatchup { Image(systemName: "clock.arrow.circlepath").foregroundStyle(Palette.accent) }
                        if ch.locked { Image(systemName: "lock.fill") }
                    }
                    .font(.caption2)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(width: channelWidth - 8, height: rowHeight)
            .background(focusedRow ? Palette.surfaceFocus : Palette.surface, in: RoundedRectangle(cornerRadius: 12))
            .padding(.leading, 0)

            // Invisible edge targets: reaching them scrolls the time window.
            Color.clear.frame(width: 8, height: rowHeight).focusable().focused($focus, equals: .left(ch.id))

            HStack(spacing: 0) {
                ForEach(model.segments(for: ch)) { seg in
                    segmentView(seg, channel: ch, msPerPoint: msPerPoint)
                }
            }
            .frame(height: rowHeight)

            Color.clear.frame(width: 4, height: rowHeight).focusable().focused($focus, equals: .right(ch.id))
        }
    }

    @ViewBuilder
    private func segmentView(_ seg: GuideSegment, channel: Channel, msPerPoint: Double) -> some View {
        switch seg {
        case let .programme(p, width):
            let w = max(CGFloat(Double(width) / msPerPoint) - 4, 4)
            Button { activate(channel, p) } label: {
                ProgrammeCell(programme: p, channel: channel, now: now, width: w)
            }
            .buttonStyle(GuideCellStyle(live: p.isLive(now: now)))
            .frame(width: w, height: rowHeight)
            .padding(.trailing, 4)
            .focused($focus, equals: .cell(channel.id, seg.id))
            .contextMenu { channelMenu(channel, p) }
        case let .gap(_, width, label):
            let w = max(CGFloat(Double(width) / msPerPoint) - 4, 4)
            if label.isEmpty && model.programmes[channel.id] != nil {
                Color.clear.frame(width: w + 4, height: rowHeight)
            } else {
                Button { activate(channel, nil) } label: {
                    Text(label).font(.callout).foregroundStyle(Palette.textDim).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).padding(.horizontal, 18)
                }
                .buttonStyle(GuideCellStyle(live: false))
                .frame(width: w, height: rowHeight)
                .padding(.trailing, 4)
                .focused($focus, equals: .cell(channel.id, seg.id))
                .contextMenu { channelMenu(channel, nil) }
            }
        }
    }

    @ViewBuilder
    private func channelMenu(_ ch: Channel, _ p: Programme?) -> some View {
        Button(ch.favorite ? "Remove from Favorites" : "Add to Favorites") {
            Task { try? await app.core.setFavorite(kind: .live, sourceId: ch.sourceId, key: ch.key, favorite: !ch.favorite) }
        }
        if let p, p.stop <= now, ch.hasCatchup {
            Button("Watch from start") { activate(ch, p) }
        }
        if let p, p.isLive(now: now), ch.hasCatchup {
            Button("Restart this programme") { playCatchup(ch, p) }
        }
        Button("Play fullscreen") {
            gate.run(app.core, locked: ch.locked, title: ch.name) {
                Task {
                    guard let req = try? await app.core.playChannel(channel: ch) else { return }
                    engine.play(req)
                    LiveLineup.start(model.channels, current: ch)
                    fullscreen = true
                }
            }
        }
        Button("Hide channel") { Task { try? await app.core.setHidden(kind: .live, sourceId: ch.sourceId, key: ch.key, hidden: true) } }
        if app.core.hasPin() {
            Button(ch.locked ? "Unlock channel" : "Lock channel") {
                gate.run(app.core, locked: true, title: "Change lock") {
                    Task { try? await app.core.setLocked(kind: .live, sourceId: ch.sourceId, key: ch.key, locked: !ch.locked) }
                }
            }
        }
    }

    // MARK: focus & actions

    private func handleFocus(_ f: GuideFocus?) {
        switch f {
        case let .cell(chId, segId):
            model.focusedChannel = model.channels.first { $0.id == chId }
            let list = model.programmes[chId] ?? []
            model.focusedProgramme = segId > 0 ? list.first { $0.start == segId } : nil
            if let p = model.focusedProgramme { lastCellTime = max(p.start, model.windowStart) }
        case let .right(chId):
            model.shiftWindow(by: hour)
            refocus(chId, at: model.windowStart + viewportMs - hour)
        case let .left(chId):
            if model.canGoBack {
                model.shiftWindow(by: -hour)
                refocus(chId, at: model.windowStart + hour - 1)
            } else {
                openGroups()
            }
        default:
            break
        }
    }

    /// After the window moves, put focus on the cell covering `time` in the same row.
    private func refocus(_ chId: Int64, at time: Int64) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            guard let ch = model.channels.first(where: { $0.id == chId }) else { return }
            let segs = model.segments(for: ch)
            let target = segs.first { seg in
                switch seg {
                case let .programme(p, _): return p.start <= time && p.stop > time
                case let .gap(s, w, label): return !label.isEmpty && s <= time && s + w > time
                }
            } ?? segs.first { if case .programme = $0 { return true } else { return false } }
            if let target { focus = .cell(chId, target.id) }
        }
    }

    private func activate(_ ch: Channel, _ p: Programme?) {
        let t = Fmt.nowMs()
        gate.run(app.core, locked: ch.locked, title: ch.name) {
            if let p, p.stop <= t, ch.hasCatchup {
                playCatchup(ch, p)
                return
            }
            if engine.current?.isLive == true, engine.current?.channelId?.int64Value == ch.id {
                LiveLineup.start(model.channels, current: ch)
                fullscreen = true
                return
            }
            Task {
                do {
                    engine.play(try await app.core.playChannel(channel: ch))
                } catch {
                    message = error.localizedDescription
                }
            }
        }
    }

    private func playCatchup(_ ch: Channel, _ p: Programme) {
        gate.run(app.core, locked: ch.locked, title: ch.name) {
            Task {
                do {
                    if let req = try await app.core.playCatchup(channel: ch, programme: p) {
                        catchup = req
                    } else {
                        message = "Catch-up isn't available for this programme"
                    }
                } catch {
                    message = error.localizedDescription
                }
            }
        }
    }

    private func openGroups() {
        showGroups = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            if let g = model.group { focus = .group(g.id) }
        }
    }

    private func closeGroups() {
        showGroups = false
        if let ch = model.focusedChannel ?? model.channels.first {
            refocus(ch.id, at: lastCellTime)
        }
    }

    // MARK: groups panel

    private var groupsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Groups").font(.title3.weight(.semibold)).padding(.leading, 22).padding(.bottom, 10)
            ScrollView(showsIndicators: false) {
                VStack(spacing: 6) {
                    ForEach(model.groups, id: \.id) { g in
                        Button {
                            gate.run(app.core, locked: g.locked, title: g.name) {
                                Task {
                                    await model.select(g)
                                    showGroups = false
                                    if let first = model.channels.first {
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { refocus(first.id, at: Fmt.nowMs()) }
                                    }
                                }
                            }
                        } label: {
                            HStack {
                                Text(icon(g) + g.name).lineLimit(1)
                                Spacer()
                                if g.locked { Image(systemName: "lock.fill").font(.caption) }
                                if let s = g.sourceName { Text(s).font(.caption).foregroundStyle(Palette.textDim) }
                            }
                        }
                        .buttonStyle(RowButtonStyle(selected: g.id == model.group?.id))
                        .focused($focus, equals: .group(g.id))
                        .contextMenu { groupMenu(g) }
                    }
                }
                .padding(.horizontal, 10)
            }
        }
        .padding(.top, 30)
        .frame(width: 520)
        .frame(maxHeight: .infinity)
        .background(Palette.surface)
        .focusSection()
    }

    @ViewBuilder
    private func groupMenu(_ g: ChannelGroup) -> some View {
        if g.type == .category {
            Button("Move up") { Task { try? await app.core.moveGroup(group: g, delta: -1) } }
            Button("Move down") { Task { try? await app.core.moveGroup(group: g, delta: 1) } }
            Button("Hide group") { Task { try? await app.core.setGroupHidden(group: g, hidden: true) } }
            if app.core.hasPin() {
                Button(g.locked ? "Unlock group" : "Lock group") {
                    gate.run(app.core, locked: true, title: "Change lock") {
                        Task { try? await app.core.setGroupLocked(group: g, locked: !g.locked) }
                    }
                }
            }
        }
    }

    private func icon(_ g: ChannelGroup) -> String {
        switch g.type {
        case .favorites: return "★  "
        case .recents: return "↺  "
        case .all: return "≡  "
        default: return ""
        }
    }
}

struct IdentifiedRequest: Identifiable {
    let request: PlaybackRequest
    var id: String { request.url }
}

private struct GuideHeader: View {
    @ObservedObject var model: GuideModel
    let now: Int64
    @EnvironmentObject private var engine: PlayerEngine

    var body: some View {
        HStack(alignment: .top, spacing: 40) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Text(model.group?.name ?? "").foregroundStyle(Palette.accent)
                    if let ch = model.focusedChannel {
                        Text("·  \(ch.number.map { "\($0)  " } ?? "")\(ch.name)").foregroundStyle(Palette.textDim).lineLimit(1)
                    }
                }
                .font(.callout.weight(.medium))
                Text(model.focusedProgramme?.title ?? model.focusedChannel?.name ?? "")
                    .font(.title2.weight(.semibold)).lineLimit(1)
                if let p = model.focusedProgramme {
                    HStack(spacing: 12) {
                        if p.isLive(now: now) { Pill(text: "LIVE", background: Palette.live.opacity(0.2), foreground: Palette.live) }
                        else if p.stop <= now, model.focusedChannel?.hasCatchup == true { Pill(text: "CATCH-UP") }
                        Text([Fmt.range(p.start, p.stop), p.episodeNum, p.category].compactMap { $0 }.joined(separator: "  ·  "))
                            .foregroundStyle(Palette.textDim)
                    }
                    .font(.callout)
                    if p.isLive(now: now) { ProgressLine(fraction: Double(p.progress(now: now))).frame(width: 340) }
                    Text(p.description_ ?? "").font(.callout).foregroundStyle(Palette.textDim).lineLimit(3)
                } else if model.focusedChannel != nil {
                    Text("No programme information").foregroundStyle(Palette.textDim)
                }
            }
            Spacer(minLength: 0)
            ZStack {
                Color.black
                if engine.current?.isLive == true {
                    VideoSurface(player: engine.player)
                    if let s = engine.status { Text(s).font(.caption).padding(8).frame(maxHeight: .infinity, alignment: .bottom) }
                } else {
                    Text("Select a programme to preview").font(.callout).foregroundStyle(Palette.textDim)
                }
            }
            .frame(width: 480, height: 270)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.outline))
        }
        .padding(.horizontal, 40)
        .padding(.top, 20)
        .frame(height: 320)
    }
}

private struct ProgrammeCell: View {
    let programme: Programme
    let channel: Channel
    let now: Int64
    let width: CGFloat
    @Environment(\.isFocused) private var focused

    var body: some View {
        let past = programme.stop <= now
        let catchup = channel.hasCatchup && past && programme.start > now - Int64(channel.catchupDays) * 24 * hour
        ZStack(alignment: .bottomLeading) {
            if width > 50 {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        if catchup { Image(systemName: "clock.arrow.circlepath").font(.caption2) }
                        Text(programme.title).font(.callout.weight(.medium)).lineLimit(1)
                    }
                    if width > 120 {
                        Text(Fmt.time(programme.start)).font(.caption2).opacity(0.7)
                    }
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .opacity(past && !catchup && !focused ? 0.6 : 1)
            }
            if programme.isLive(now: now) {
                ProgressLine(fraction: Double(programme.progress(now: now)), color: focused ? Palette.onAccent : Palette.accent, height: 3)
                    .padding(.horizontal, 12).padding(.bottom, 8)
            }
        }
    }
}

/// Programme cells: flat surfaces that turn accent-coloured when focused (no lift, so the grid stays aligned).
private struct GuideCellStyle: ButtonStyle {
    let live: Bool
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 10).fill(focused ? Palette.accent : (live ? Palette.surfaceHigh : Palette.surface)))
            .foregroundStyle(focused ? Palette.onAccent : Palette.text)
    }
}
