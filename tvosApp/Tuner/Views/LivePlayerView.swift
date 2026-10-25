import AVFoundation
import Combine
import SwiftUI
import TunerCore
import UIKit

/// The lineup the fullscreen player zaps through (the group it was opened from).
@MainActor
enum LiveLineup {
    static var channels: [Channel] = []
    static var index = 0

    static func start(_ lineup: [Channel], current: Channel) {
        channels = lineup.isEmpty ? [current] : lineup
        index = channels.firstIndex { $0.id == current.id } ?? 0
    }

    static var current: Channel? { channels.indices.contains(index) ? channels[index] : nil }
}

/// Fullscreen live TV. Siri Remote: swipe/click up-down zaps, select opens the
/// channel list, left/right shows the info bar, play/pause pauses, long-press
/// opens options, Menu closes overlays and then the player.
struct LivePlayerView: View {
    let lineup: [Channel]

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @Environment(\.dismiss) private var dismiss
    @StateObject private var gate = PinGate()
    @State private var channel: Channel? = LiveLineup.current
    @State private var nowNext: [Programme] = []
    @State private var showInfo = true
    @State private var showList = false
    @State private var showOptions = false
    @State private var showTracks = false
    /// Set when a hold opened the options, so the release that follows doesn't also open the channel list.
    @State private var held = false
    @State private var showSleep = false
    @State private var fill = false
    @State private var infoTask: Task<Void, Never>?
    @State private var message: String?
    @State private var tuneTask: Task<Void, Never>?
    @State private var lastZap = Date.distantPast
    /// Audio and subtitle tracks of the current item, loaded when their dialog opens.
    @State private var tracks: (audio: AVMediaSelectionGroup?, legible: AVMediaSelectionGroup?) = (nil, nil)
    @FocusState private var focus: LiveFocus?

    enum LiveFocus: Hashable { case surface, channel(Int64) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: engine.player, gravity: fill ? .resizeAspectFill : .resizeAspect).ignoresSafeArea()

            // Full-screen focus target that receives remote commands while no overlay is open.
            Button { if held { held = false } else { showList = true } } label: { Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(InvisibleButtonStyle())
                // Neither a long-press gesture, a context menu nor the button's pressed state sees
                // every hold here on tvOS (the first press after a dialog closes arrives without its
                // press-down), so a UIKit recognizer on the window detects it.
                .background(SelectHoldDetector(enabled: !showList && !showOptions && !showSleep && !showTracks) {
                    held = true
                    showOptions = true
                })
                .focused($focus, equals: .surface)
                .onMoveCommand { dir in
                    switch dir {
                    case .up: zap(-1)
                    case .down: zap(1)
                    default: flashInfo()
                    }
                }
                .onPlayPauseCommand {
                    engine.togglePause()
                    flashInfo()
                }
                .disabled(showList)

            #if DEBUG
            PlayerHUD(engine: engine).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            #endif

            if engine.isBuffering || engine.status != nil {
                Text(engine.status ?? "Buffering…")
                    .padding(24).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
            }

            if showInfo, let channel {
                InfoBar(channel: channel, nowNext: nowNext)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if showList { channelList.transition(.move(edge: .leading)) }
            if let message { Toast(text: message).frame(maxHeight: .infinity, alignment: .top).padding(.top, 60) }
        }
        .animation(.easeOut(duration: 0.2), value: showInfo)
        .animation(.easeOut(duration: 0.2), value: showList)
        .onExitCommand {
            if showList { showList = false; focus = .surface }
            else if showInfo { showInfo = false }
            else { dismiss() }
        }
        .onAppear {
            focus = .surface
            flashInfo()
        }
        .task(id: channel?.id) {
            guard let ch = channel else { return }
            nowNext = (try? await app.core.upcoming(channel: ch, limit: 2)) ?? []
        }
        .task(id: message) {
            guard message != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            message = nil
        }
        .confirmationDialog(channel?.name ?? "Options", isPresented: $showOptions) { optionsMenu }
        .confirmationDialog("Audio and subtitles", isPresented: $showTracks) { tracksMenu }
        .onChange(of: showOptions) { _, open in if !open { held = false } }
        .confirmationDialog("Sleep timer", isPresented: $showSleep) {
            Button("Off") { engine.setSleepTimer(minutes: 0) }
            ForEach(SleepTimer.minutes, id: \.self) { m in Button("\(m) minutes") { engine.setSleepTimer(minutes: m) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Stops playback after the time you choose") }
        .onChange(of: engine.sleepFired) { _, _ in dismiss() }
        .task(id: showTracks) {
            guard showTracks, let asset = engine.player.currentItem?.asset else { return }
            tracks = (try? await asset.loadMediaSelectionGroup(for: .audible), try? await asset.loadMediaSelectionGroup(for: .legible))
        }
        .pinGate(gate)
    }

    @ViewBuilder
    private var optionsMenu: some View {
        if let ch = channel {
            Button(ch.favorite ? "Remove from Favorites" : "Add to Favorites") {
                Task {
                    try? await app.core.setFavorite(kind: .live, sourceId: ch.sourceId, key: ch.key, favorite: !ch.favorite)
                    channel = try? await app.core.channel(id: ch.id)
                }
            }
        }
        Button(fill ? "Picture: Fit" : "Picture: Fill") { fill.toggle() }
        Button("Sleep timer: \(SleepTimer.label(engine.sleepAt))") {
            // Let this dialog close before the next one opens.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showSleep = true }
        }
        Button("Previous channel") {
            Task { if let prev = try? await app.core.previousChannel() { tune(prev) } else { message = "No previous channel" } }
        }
        Button("Audio and subtitles") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showTracks = true }
        }
        Button("Cancel", role: .cancel) {}
    }

    @ViewBuilder
    private var tracksMenu: some View {
        if let item = engine.player.currentItem {
            if let group = tracks.audio {
                ForEach(Array(group.options.enumerated()), id: \.offset) { _, opt in
                    Button("Audio: \(opt.displayName)") { item.select(opt, in: group) }
                }
            }
            if let group = tracks.legible {
                ForEach(Array(group.options.enumerated()), id: \.offset) { _, opt in
                    Button("Subtitles: \(opt.displayName)") { item.select(opt, in: group) }
                }
            }
        }
        Button("Cancel", role: .cancel) {}
    }

    private var channelList: some View {
        ChannelListPanel(current: channel, focus: $focus) { idx, ch in
            showList = false
            focus = .surface
            tune(ch, index: idx)
        }
    }

    private func flashInfo() {
        showInfo = true
        infoTask?.cancel()
        let timeout = UInt64(app.core.settings.overlayTimeoutSec) * 1_000_000_000
        infoTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: timeout)
            if !Task.isCancelled { showInfo = false }
        }
    }

    private func zap(_ delta: Int) {
        let list = LiveLineup.channels
        guard !list.isEmpty else { return }
        let next = ((LiveLineup.index + delta) % list.count + list.count) % list.count
        let rapid = Date().timeIntervalSince(lastZap) < Self.zapSettle * 2
        lastZap = Date()
        LiveLineup.index = next
        tune(list[next], index: next, debounce: rapid)
    }

    /// Zaps closer together than this are coalesced into one tune.
    private static let zapSettle: TimeInterval = 0.35

    private func tune(_ ch: Channel, index: Int? = nil, debounce: Bool = false) {
        gate.run(app.core, locked: ch.locked, title: ch.name) {
            tuneTask?.cancel()
            tuneTask = Task {
                // While the user is flicking through channels, only the one they stop on is
                // opened: a single-connection account would otherwise be refused mid-zap.
                if debounce {
                    channel = ch
                    flashInfo()
                    try? await Task.sleep(nanoseconds: UInt64(Self.zapSettle * 1_000_000_000))
                    guard !Task.isCancelled else { return }
                }
                do {
                    engine.play(try await app.core.playChannel(channel: ch))
                    LiveLineup.index = index ?? LiveLineup.channels.firstIndex { $0.id == ch.id } ?? LiveLineup.index
                    // The lineup holds copies from when it opened; favorites may have changed since.
                    channel = (try? await app.core.channel(id: ch.id)) ?? ch
                    flashInfo()
                } catch {
                    message = error.localizedDescription
                }
            }
        }
    }
}

/// The channel list overlay. Kept as its own view so loading "now playing" titles only
/// re-renders the list, and titles are fetched in one guide query per batch of rows that
/// scroll into view instead of one query per row.
private struct ChannelListPanel: View {
    let current: Channel?
    let focus: FocusState<LivePlayerView.LiveFocus?>.Binding
    let select: (Int, Channel) -> Void

    @EnvironmentObject private var app: AppModel
    @State private var nowPlaying: [Int64: String] = [:]
    @State private var pending: [Int64: Channel] = [:]
    @State private var batchScheduled = false

    var body: some View {
        let channels = LiveLineup.channels
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 6) {
                    ForEach(0..<channels.count, id: \.self) { idx in
                        let ch = channels[idx]
                        Button { select(idx, ch) } label: {
                            HStack(spacing: 16) {
                                Text(verbatim: Fmt.number(ch.number)).foregroundStyle(Palette.textDim).lineLimit(1).fixedSize().frame(minWidth: 56, alignment: .leading)
                                Artwork(url: ch.logo, title: ch.name, contentMode: .fit, fontSize: 14)
                                    .frame(width: 64, height: 44).clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(ch.name).lineLimit(1)
                                    if let now = nowPlaying[ch.id], !now.isEmpty {
                                        Text(now).font(.caption).foregroundStyle(Palette.textDim).lineLimit(1)
                                    }
                                }
                                Spacer()
                                if ch.locked { Image(systemName: "lock.fill").font(.caption) }
                            }
                        }
                        .buttonStyle(RowButtonStyle(selected: ch.id == current?.id))
                        .focused(focus, equals: .channel(ch.id))
                        .id(ch.id)
                        .onAppear { request(ch) }
                    }
                }
                .padding(20)
            }
            .frame(width: 640)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [.black.opacity(0.95), .black.opacity(0.7), .clear], startPoint: .leading, endPoint: .trailing))
            .onAppear {
                if let id = current?.id {
                    proxy.scrollTo(id, anchor: .center)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { focus.wrappedValue = .channel(id) }
                }
            }
        }
    }

    private func request(_ ch: Channel) {
        guard nowPlaying[ch.id] == nil, pending[ch.id] == nil else { return }
        pending[ch.id] = ch
        guard !batchScheduled else { return }
        batchScheduled = true
        Task { @MainActor in
            // Collect the rows appearing in this scroll step, then query them together.
            try? await Task.sleep(nanoseconds: 40_000_000)
            let rows = Array(pending.values)
            pending = [:]
            batchScheduled = false
            let now = Fmt.nowMs()
            let guide = (try? await app.core.guide(channels: rows, from: now, to: now + 1)) ?? []
            var update = nowPlaying
            for r in guide { update[r.channel.id] = r.programmes.first?.title ?? "" }
            nowPlaying = update
        }
    }
}

private struct InfoBar: View {
    let channel: Channel
    let nowNext: [Programme]

    var body: some View {
        let now = Fmt.nowMs()
        let current = nowNext.first { $0.isLive(now: now) }
        let next = nowNext.first { $0.start >= (current?.stop ?? now) }
        HStack(spacing: 30) {
            Artwork(url: channel.logo, title: channel.name, contentMode: .fit, fontSize: 24)
                .frame(width: 150, height: 100).clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 14) {
                    if channel.number != nil { Text(verbatim: Fmt.number(channel.number)).foregroundStyle(Palette.accent) }
                    Text(channel.name)
                    Pill(text: "LIVE", background: Palette.live.opacity(0.25), foreground: Palette.live)
                    if channel.favorite { Text("★").foregroundStyle(Palette.warning) }
                    Spacer()
                    ClockText()
                }
                .font(.title3.weight(.semibold))
                if let p = current {
                    Text(p.title).font(.headline)
                    HStack(spacing: 16) {
                        Text(Fmt.range(p.start, p.stop)).foregroundStyle(Palette.textDim)
                        ProgressLine(fraction: Double(p.progress(now: now))).frame(width: 360)
                    }
                    .font(.callout)
                } else {
                    Text("No programme information").foregroundStyle(Palette.textDim)
                }
                if let n = next {
                    Text("Next  \(Fmt.range(n.start, n.stop))  \(n.title)").font(.callout).foregroundStyle(Palette.textDim)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 80)
        .padding(.top, 80)
        .padding(.bottom, 60)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.92)], startPoint: .top, endPoint: .bottom))
    }
}

struct InvisibleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

/// Calls `onHold` when the select button is held, wherever focus is, while `enabled`. The
/// recognizer sits on the window (presses travel up from the focused view) and is removed with
/// this view.
struct SelectHoldDetector: UIViewRepresentable {
    var enabled: Bool
    let onHold: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> HostView {
        let v = HostView()
        v.isUserInteractionEnabled = false
        v.recognizer = context.coordinator.recognizer
        return v
    }

    func updateUIView(_ v: HostView, context: Context) {
        context.coordinator.onHold = onHold
        context.coordinator.recognizer.isEnabled = enabled
    }

    static func dismantleUIView(_ v: HostView, coordinator: Coordinator) {
        coordinator.recognizer.view?.removeGestureRecognizer(coordinator.recognizer)
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onHold: () -> Void = {}
        lazy var recognizer: UILongPressGestureRecognizer = {
            let r = UILongPressGestureRecognizer(target: self, action: #selector(fire(_:)))
            r.allowedPressTypes = [NSNumber(value: UIPress.PressType.select.rawValue)]
            r.minimumPressDuration = 0.6
            r.cancelsTouchesInView = false
            r.delegate = self
            return r
        }()

        /// SwiftUI's own press handling is running at the same time; neither may block the other.
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        @objc private func fire(_ r: UILongPressGestureRecognizer) {
            if r.state == .began { onHold() }
        }
    }

    final class HostView: UIView {
        var recognizer: UIGestureRecognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window, let recognizer { window.addGestureRecognizer(recognizer) }
        }
    }
}

/// Movies, episodes and catch-up in the system player. Progress is saved every 10 s and on exit.
/// When an episode ends, the next one plays after a short countdown; the player's menu bar has a sleep timer.
struct VodPlayerView: View {
    let request: PlaybackRequest

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var engine: PlayerEngine
    @Environment(\.dismiss) private var dismiss
    @State private var saver: Task<Void, Never>?
    @State private var current: PlaybackRequest?
    @State private var upNext: PlaybackRequest?
    @State private var countdown = Self.upNextSeconds

    private static let upNextSeconds = 10

    var body: some View {
        let playing = current ?? request
        ZStack {
            if let next = upNext {
                UpNextCard(next: next, countdown: countdown, play: { playNext() }, cancel: { dismiss() })
                    .onExitCommand { dismiss() }
                    .task(id: next.url) {
                        for s in stride(from: Self.upNextSeconds, through: 1, by: -1) {
                            countdown = s
                            try? await Task.sleep(nanoseconds: 1_000_000_000)
                            if Task.isCancelled { return }
                        }
                        playNext()
                    }
            } else {
                SystemPlayerView(player: engine.player, title: playing.title, subtitle: playing.subtitle, menu: sleepMenu)
                    .ignoresSafeArea()
            }
        }
        #if DEBUG
        .overlay(alignment: .topLeading) { PlayerHUD(engine: engine) }
        #endif
        .overlay(alignment: .top) {
            if let s = engine.status, upNext == nil { Toast(text: s).padding(.top, 60) }
        }
        .onAppear {
            current = request
            engine.play(request)
            saver = Task { @MainActor in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                    if engine.player.timeControlStatus == .playing { save() }
                }
            }
        }
        .onDisappear {
            saver?.cancel()
            if upNext == nil { save() }
            engine.stop()
        }
        .onChange(of: engine.sleepFired) { _, _ in dismiss() }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { note in
            guard (note.object as? AVPlayerItem) === engine.player.currentItem, upNext == nil else { return }
            let finished = playing, d = engine.durationMs
            Task {
                try? await app.core.saveProgress(request: finished, positionMs: d, durationMs: d)
                let next = app.core.settings.autoplayNextEpisode ? try? await app.core.nextEpisode(request: finished) : nil
                if let next { upNext = next } else { dismiss() }
            }
        }
    }

    private func playNext() {
        guard let next = upNext else { return }
        current = next
        upNext = nil
        engine.play(next)
    }

    private var sleepMenu: UIMenu {
        let at = engine.sleepAt
        let off = UIAction(title: "Off", state: at == nil ? .on : .off) { [engine] _ in engine.setSleepTimer(minutes: 0) }
        let options = SleepTimer.minutes.map { m in
            UIAction(title: "\(m) minutes") { [engine] _ in engine.setSleepTimer(minutes: m) }
        }
        return UIMenu(title: "Sleep timer: \(SleepTimer.label(at))", image: UIImage(systemName: "moon.zzz"), children: [off] + options)
    }

    private func save() {
        let pos = engine.positionMs, dur = engine.durationMs
        let core = app.core, req = current ?? request
        Task.detached { try? await core.saveProgress(request: req, positionMs: pos, durationMs: dur) }
    }
}

/// Shown when an episode ends: the next one starts after a countdown unless cancelled.
private struct UpNextCard: View {
    let next: PlaybackRequest
    let countdown: Int
    let play: () -> Void
    let cancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Text("Up next · playing in \(countdown) s").font(.callout).foregroundStyle(Palette.textDim)
                Text(next.title).font(.title2.weight(.semibold)).lineLimit(2)
                if let sub = next.subtitle { Text(sub).foregroundStyle(Palette.textDim) }
                HStack(spacing: 30) {
                    Button("Play now", action: play).focused($focused)
                    Button("Cancel", action: cancel)
                }
                .padding(.top, 10)
            }
            .padding(40)
            .frame(width: 760, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 24))
            .padding(80)
        }
        .onAppear { focused = true }
    }
}

#if DEBUG
struct PlayerHUD: View {
    let engine: PlayerEngine
    @State private var text = ""
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Group {
            if ProcessInfo.processInfo.arguments.contains("-playerHUD") {
                Text(text).font(.system(size: 20, design: .monospaced)).padding(10).background(.black.opacity(0.7)).padding(30)
            }
        }
        .onReceive(tick) { _ in text = engine.debugSummary }
    }
}
#endif
