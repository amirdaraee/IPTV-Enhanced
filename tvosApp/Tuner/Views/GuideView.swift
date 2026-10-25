import Combine
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
    private var pendingRows: [Int64: Channel] = [:]
    private var batchScheduled = false
    private var subscription: TunerCore.Subscription?
    weak var app: AppModel?

    /// Subscribes to the changes the guide shows; the first callback does the initial load.
    func start(app: AppModel) {
        guard self.app == nil else { return }
        self.app = app
        subscription = app.watch(Changes.catalog | Changes.prefs | Changes.history) { [weak self] mask in
            Task { await self?.changed(mask) }
        }
    }

    deinit { subscription?.cancel() }

    private func changed(_ mask: Int32) async {
        // Zapping only touches history: the open lineup is unaffected unless it is Recents,
        // and the group list only needs refreshing for Recents to appear.
        if mask == Changes.history {
            if group?.type == .recents { await load(group, reset: false) }
            else if !groups.contains(where: { $0.type == .recents }) { await reload() }
            return
        }
        await reload()
    }

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
        // Never auto-open a locked group; the user must pick it and enter the PIN.
        let allowed = visible.filter { core.isAllowed(locked: $0.locked) }
        let next = allowed.first { $0.id == keep }
            ?? allowed.first { $0.type == .favorites }
            ?? allowed.first { $0.type == .category }
            ?? allowed.first
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
        if !same {
            // Rows keep their identity (channel id) across groups and don't reappear, so keep
            // what is loaded for channels in both lineups and drop the rest.
            let ids = Set(list.map(\.id))
            requested = requested.filter(ids.contains)
            programmes = programmes.filter { ids.contains($0.key) }
        }
        await refreshLoaded()
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
        merge(fresh)
    }

    /// One assignment per batch, so the grid re-renders once rather than once per row.
    private func merge(_ rows: [GuideRow]) {
        guard !rows.isEmpty else { return }
        var update = programmes
        for r in rows { update[r.channel.id] = r.programmes }
        programmes = update
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
        pendingRows[channel.id] = channel
        guard !batchScheduled else { return }
        batchScheduled = true
        // Debounce without cancelling: a query already in flight must still deliver its rows.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 30_000_000)
            let rows = Array(pendingRows.values)
            pendingRows = [:]
            batchScheduled = false
            guard let core = app?.core, !rows.isEmpty else { return }
            let f = loadedFrom, t = loadedTo
            let fresh = (try? await core.guide(channels: rows, from: f, to: t)) ?? []
            if f == loadedFrom { merge(fresh) }
        }
    }

    func shiftWindow(by delta: Int64) {
        let minStart = align(Fmt.nowMs() - Int64(app?.core.settings.guidePastDays ?? 2) * 24 * hour)
        let maxStart = Fmt.nowMs() + Int64(app?.core.settings.guideFutureDays ?? 7) * 24 * hour
        windowStart = min(max(windowStart + delta, minStart), maxStart)
        for c in channels where requested.contains(c.id) { requested.remove(c.id); ensureLoaded(c) }
    }

    /// The guide's past limit, from the settings.
    var minStart: Int64 { align(Fmt.nowMs() - Int64(app?.core.settings.guidePastDays ?? 2) * 24 * hour) }

    func segments(for channel: Channel) -> [GuideSegment] {
        guideSegments(programmes[channel.id], windowStart: windowStart)
    }
}

/// Visible segments for a row: programmes clipped to the window, with gaps between them.
func guideSegments(_ programmes: [Programme]?, windowStart: Int64) -> [GuideSegment] {
    let windowEnd = windowStart + viewportMs
    guard let list = programmes else { return [.gap(start: windowStart, width: viewportMs, label: "")] }
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
    case channel(Int64)
    case left(Int64)
    case right(Int64)
    case group(String)
}

/// The group list is a permanent side menu. Navigation runs groups → channel names → programme grid:
/// the tab opens on the group list, picking a group moves to its channel names (OK plays the channel
/// live, fullscreen), and Right enters the grid.
struct GuideView: View {
    /// True while the Live TV tab is selected; selecting the tab again moves focus to the group list.
    var active = true
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @StateObject private var model = GuideModel()
    @StateObject private var gate = PinGate()
    @FocusState private var focus: GuideFocus?
    @State private var fullscreen = false
    @State private var catchup: PlaybackRequest?
    @State private var message: String?
    @State private var now = Fmt.nowMs()
    @State private var lastCellTime: Int64 = Fmt.nowMs()
    @State private var previousFocus: GuideFocus?
    @State private var focusedOnce = false

    private let ticker = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: 40) {
            groupsPanel
            GeometryReader { geo in
                let msPerPoint = Double(viewportMs) / Double(geo.size.width - channelWidth - 16)
                VStack(spacing: 0) {
                    GuideHeader(model: model, now: now)
                    ruler(msPerPoint: msPerPoint)
                    if model.loaded && model.channels.isEmpty {
                        EmptyStateView(
                            title: model.group?.type == .favorites ? "No favorites yet" : "No channels here",
                            message: "Choose another group on the left, or press and hold on a programme to add its channel to Favorites."
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .focusable()
                    } else {
                        grid(msPerPoint: msPerPoint)
                    }
                }
            }
        }
        .background(Palette.background)
        .overlay(alignment: .bottom) { if let message { Toast(text: message).padding(.bottom, 40) } }
        // Back steps out one level: grid → channel name → group list → tab bar (system default).
        .onExitCommand(perform: inGroups ? nil : back)
        .onChange(of: focus) { old, f in
            previousFocus = old
            handleFocus(f)
        }
        .onChange(of: model.channels.first?.id) { _, first in
            guard let first else { return }
            if focus != nil, !inGroups {
                focusChannel(first)
            } else if !focusedOnce, let g = model.group {
                // First load: start on the open group rather than the tab bar.
                focusedOnce = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { focus = .group(g.id) }
            }
        }
        .onChange(of: active) { _, isActive in
            if isActive { openGroups() }
        }
        .onReceive(ticker) { _ in now = Fmt.nowMs() }
        .onPlayPauseCommand { jumpToNow() }
        .task {
            model.start(app: app)
            await startLastChannel()
        }
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
                    // Identity by index keeps diffing cheap for 20k-channel lineups (no id
                    // walk over every Kotlin object on each update), and equatable rows mean
                    // a remote press only re-renders the rows whose focus actually changed.
                    ForEach(0..<model.channels.count, id: \.self) { i in
                        let ch = model.channels[i]
                        GuideRowView(
                            channel: ch,
                            programmes: model.programmes[ch.id],
                            windowStart: model.windowStart,
                            msPerPoint: msPerPoint,
                            now: now,
                            focusedRow: model.focusedChannel?.id == ch.id,
                            playing: engine.current?.channelId?.int64Value == ch.id,
                            focus: $focus,
                            activate: activate,
                            watch: watch,
                            openGroups: openGroups,
                            menu: { ch, p in AnyView(channelMenu(ch, p)) }
                        )
                        .equatable()
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
        Button("Play fullscreen") { watch(ch) }
        Button("Earlier programmes") { showEarlier(ch.id) }
        if model.windowStart != align(now) {
            Button("Back to now") {
                model.shiftWindow(by: align(Fmt.nowMs()) - model.windowStart)
                refocus(ch.id, at: Fmt.nowMs())
            }
        }
        if model.group?.type == .favorites {
            Button("Move up") { Task { try? await app.core.moveFavorite(channel: ch, delta: -1) } }
            Button("Move down") { Task { try? await app.core.moveFavorite(channel: ch, delta: 1) } }
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
        case let .channel(chId):
            // The header shows what is on now; Right from here enters the grid at the live cell.
            let t = Fmt.nowMs()
            model.focusedChannel = model.channels.first { $0.id == chId }
            model.focusedProgramme = model.programmes[chId]?.first { $0.start <= t && $0.stop > t }
            lastCellTime = min(max(t, model.windowStart), model.windowStart + viewportMs - 1)
        case let .right(chId), let .left(chId):
            // Edge targets only scroll time when reached by moving sideways from a cell in the
            // same row; any other arrival (initial focus, vertical moves) goes to the live cell.
            guard case let .cell(prevCh, _) = previousFocus, prevCh == chId else {
                refocus(chId, at: max(lastCellTime, model.windowStart))
                return
            }
            if case .right = f { scrollRight(chId) } else { scrollLeft(chId) }
        default:
            break
        }
    }

    private func scrollRight(_ chId: Int64) {
        model.shiftWindow(by: hour)
        refocus(chId, at: model.windowStart + viewportMs - hour)
    }

    /// Scrolls back towards now; once the window shows now, Left goes to the channel name.
    /// Left at the window's edge. From later programmes it scrolls back towards now, and while browsing
    /// earlier programmes it keeps going back; at now it goes to the channel name.
    private func scrollLeft(_ chId: Int64) {
        let nowStart = align(Fmt.nowMs())
        if model.windowStart > nowStart || (model.windowStart < nowStart && model.windowStart > model.minStart) {
            model.shiftWindow(by: -hour)
            refocus(chId, at: model.windowStart + hour - 1)
        } else {
            focusChannel(chId)
        }
    }

    /// Play/Pause: the grid back on the current time.
    private func jumpToNow() {
        model.shiftWindow(by: align(Fmt.nowMs()) - model.windowStart)
        if case let .cell(chId, _) = focus { refocus(chId, at: Fmt.nowMs()) }
    }

    /// The two hours before the visible window, focused on its first programme in this row.
    private func showEarlier(_ chId: Int64) {
        model.shiftWindow(by: -2 * hour)
        refocus(chId, at: model.windowStart)
    }

    private func focusChannel(_ chId: Int64) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            if model.channels.contains(where: { $0.id == chId }) { focus = .channel(chId) }
        }
    }

    private func back() {
        switch focus {
        case let .cell(chId, _), let .left(chId), let .right(chId): focusChannel(chId)
        default: openGroups()
        }
    }

    /// After the window moves, put focus on the cell covering `time` in the same row.
    /// Retries briefly while the row's programmes are still loading.
    private func refocus(_ chId: Int64, at time: Int64, attempt: Int = 0) {
        DispatchQueue.main.asyncAfter(deadline: .now() + (attempt == 0 ? 0.05 : 0.3)) {
            guard let ch = model.channels.first(where: { $0.id == chId }) else { return }
            if model.programmes[chId] == nil, attempt < 6 {
                refocus(chId, at: time, attempt: attempt + 1)
                return
            }
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

    /// "Start with last channel": once per launch, open the last channel watched in fullscreen.
    private func startLastChannel() async {
        guard !app.startedLastChannel else { return }
        app.startedLastChannel = true
        guard app.core.settings.autoplayLastChannel, let ch = try? await app.core.lastChannel(),
              app.core.isAllowed(locked: ch.locked), let req = try? await app.core.playChannel(channel: ch) else { return }
        engine.play(req)
        LiveLineup.start([ch], current: ch)
        fullscreen = true
    }

    /// Plays the channel live in fullscreen, keeping the stream if it is already in the preview.
    private func watch(_ ch: Channel) {
        gate.run(app.core, locked: ch.locked, title: ch.name) {
            Task {
                if engine.current?.isLive != true || engine.current?.channelId?.int64Value != ch.id {
                    do {
                        engine.play(try await app.core.playChannel(channel: ch))
                    } catch {
                        message = error.localizedDescription
                        return
                    }
                }
                LiveLineup.start(model.channels, current: ch)
                fullscreen = true
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

    private var inGroups: Bool {
        if case .group = focus { return true }
        return false
    }

    /// Focuses the open group, rather than whichever group is nearest.
    private func openGroups() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            if let g = model.group { focus = .group(g.id) }
        }
    }

    /// Right from the group list goes to the channel last focused in this group.
    private func enterChannels() {
        if let ch = model.focusedChannel ?? model.channels.first { focusChannel(ch.id) }
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
                                    if let first = model.channels.first {
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { focusChannel(first.id) }
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
                        .onMoveCommand { if $0 == .right { enterChannels() } }
                        .contextMenu { groupMenu(g) }
                    }
                }
                .padding(.horizontal, 10)
            }
        }
        .padding(.top, 24)
        .frame(width: 340)
        .frame(maxHeight: .infinity)
        // A shaped background stays inside the safe area, so the panel starts below the tab bar.
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20))
        .padding(.top, 20)
        .focusSection()
        .defaultFocus($focus, model.group.map { .group($0.id) })
    }

    @ViewBuilder
    private func groupMenu(_ g: ChannelGroup) -> some View {
        if g.type == .category {
            Button("Add all to Favorites") {
                Task {
                    let list = (try? await app.core.channels(group: g, includeHidden: false)) ?? []
                    let added = (try? await app.core.setFavorites(channels: list, favorite: true))?.intValue ?? 0
                    message = added == 0 ? "Already in Favorites" : "Added \(added) channel\(added == 1 ? "" : "s") to Favorites"
                }
            }
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
                        Text(verbatim: "·  \(ch.number == nil ? "" : Fmt.number(ch.number) + "  ")\(ch.name)").foregroundStyle(Palette.textDim).lineLimit(1)
                    }
                    Spacer(minLength: 20)
                    ClockText().foregroundStyle(Palette.text)
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

/// One guide row. Equatable on the values it draws, so SwiftUI skips rows whose channel,
/// programmes, focus highlight and time window are unchanged.
private struct GuideRowView: View, Equatable {
    let channel: Channel
    let programmes: [Programme]?
    let windowStart: Int64
    let msPerPoint: Double
    let now: Int64
    let focusedRow: Bool
    let playing: Bool
    let focus: FocusState<GuideFocus?>.Binding
    let activate: (Channel, Programme?) -> Void
    let watch: (Channel) -> Void
    let openGroups: () -> Void
    let menu: (Channel, Programme?) -> AnyView

    static func == (a: Self, b: Self) -> Bool {
        a.channel == b.channel && a.programmes == b.programmes && a.windowStart == b.windowStart
            && a.msPerPoint == b.msPerPoint && a.now == b.now && a.focusedRow == b.focusedRow && a.playing == b.playing
    }

    var body: some View {
        let ch = channel
        HStack(spacing: 0) {
            // OK on the channel name plays it live in fullscreen, whatever part of the grid is showing.
            Button { watch(ch) } label: {
                ChannelLabel(channel: ch, playing: playing)
            }
            .buttonStyle(ChannelCellStyle(focusedRow: focusedRow))
            .frame(width: channelWidth - 8, height: rowHeight)
            .focused(focus, equals: .channel(ch.id))
            .onMoveCommand { if $0 == .left { openGroups() } }
            .contextMenu { menu(ch, nil) }

            // Invisible edge targets: reaching them scrolls the time window.
            Color.clear.frame(width: 8, height: rowHeight).focusable().focused(focus, equals: .left(ch.id))

            HStack(spacing: 0) {
                ForEach(guideSegments(programmes, windowStart: windowStart)) { seg in
                    segmentView(seg)
                }
            }
            .frame(height: rowHeight)

            Color.clear.frame(width: 4, height: rowHeight).focusable().focused(focus, equals: .right(ch.id))
        }
    }

    @ViewBuilder
    private func segmentView(_ seg: GuideSegment) -> some View {
        switch seg {
        case let .programme(p, width):
            let w = max(CGFloat(Double(width) / msPerPoint) - 4, 4)
            Button { activate(channel, p) } label: {
                ProgrammeCell(programme: p, channel: channel, now: now, width: w)
            }
            .buttonStyle(GuideCellStyle(live: p.isLive(now: now)))
            .frame(width: w, height: rowHeight)
            .padding(.trailing, 4)
            .focused(focus, equals: .cell(channel.id, seg.id))
            .contextMenu { menu(channel, p) }
        case let .gap(_, width, label):
            let w = max(CGFloat(Double(width) / msPerPoint) - 4, 4)
            if label.isEmpty && programmes != nil {
                Color.clear.frame(width: w + 4, height: rowHeight)
            } else {
                Button { activate(channel, nil) } label: {
                    GapLabel(text: label)
                }
                .buttonStyle(GuideCellStyle(live: false))
                .frame(width: w, height: rowHeight)
                .padding(.trailing, 4)
                .focused(focus, equals: .cell(channel.id, seg.id))
                .contextMenu { menu(channel, nil) }
            }
        }
    }
}

private struct ChannelLabel: View {
    let channel: Channel
    let playing: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        let ch = channel
        HStack(spacing: 14) {
            Text(verbatim: Fmt.number(ch.number)).font(.callout)
                .foregroundStyle(focused ? Palette.onAccent : (playing ? Palette.accent : Palette.textDim))
                .lineLimit(1).fixedSize().frame(minWidth: 56, alignment: .leading)
            Artwork(url: ch.logo, title: ch.name, contentMode: .fit, fontSize: 16)
                .frame(width: 70, height: 50).clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(ch.name).font(.callout.weight(.medium)).lineLimit(1)
                    .foregroundStyle(focused ? Palette.onAccent : (playing ? Palette.accent : Palette.text))
                HStack(spacing: 6) {
                    if ch.favorite { Text("★").foregroundStyle(focused ? Palette.onAccent : Palette.warning) }
                    if ch.hasCatchup { Image(systemName: "clock.arrow.circlepath").foregroundStyle(focused ? Palette.onAccent : Palette.accent) }
                    if ch.locked { Image(systemName: "lock.fill") }
                }
                .font(.caption2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
    }
}

/// Channel names: like programme cells, flat and accent-coloured when focused.
private struct ChannelCellStyle: ButtonStyle {
    let focusedRow: Bool
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 12).fill(focused ? Palette.accent : (focusedRow ? Palette.surfaceFocus : Palette.surface)))
            .foregroundStyle(focused ? Palette.onAccent : Palette.text)
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

/// "No information" placeholder: dim on the surface, dark on the accent when focused.
private struct GapLabel: View {
    let text: String
    @Environment(\.isFocused) private var focused

    var body: some View {
        Text(text).font(.callout).foregroundStyle(focused ? Palette.onAccent : Palette.textDim)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).padding(.horizontal, 18)
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
