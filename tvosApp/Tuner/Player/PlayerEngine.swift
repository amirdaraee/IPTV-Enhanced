import AVFoundation
import Combine
import TunerCore
import UIKit
#if os(tvOS)
import TVVLCKit
#else
import MobileVLCKit
#endif

/// Owns the single AVPlayer shared by the guide preview and the fullscreen players,
/// so going fullscreen never re-buffers. Adds request headers and retries live
/// streams with exponential backoff.
///
/// Streams AVPlayer can't decode (MP2 audio is common on IPTV, as are MPEG-2 video and
/// MKV files) are handed to VLCKit instead. AVPlayer stays first because it is the system
/// player: frame-rate matching, the system transport bar and lower power use.
@MainActor
final class PlayerEngine: ObservableObject {
    let player = AVPlayer()

    enum Backend { case system, vlc }
    @Published private(set) var backend: Backend = .system {
        didSet { if backend != oldValue { placeVLC() } }
    }
    /// Increments when a movie or episode plays to its end, whichever player was playing it.
    @Published private(set) var finished = 0

    @Published private(set) var current: PlaybackRequest?
    @Published private(set) var status: String?
    @Published private(set) var isBuffering = false
    /// When the sleep timer stops playback, or nil when it is off.
    @Published private(set) var sleepAt: Date?
    /// Increments each time the sleep timer fires, so open players can close.
    @Published private(set) var sleepFired = 0
    private var sleepTask: Task<Void, Never>?

    private var retries = 0
    /// Retries ran out; the item is kept (removing it leaves the system player's spinner up) but must not be reused.
    private var gaveUp = false
    /// False only while the user has paused; stalled live streams auto-resume otherwise.
    private var wantsPlayback = true
    /// URL actually being played; may differ from the request's when a .ts stream is swapped for HLS.
    private var activeURL: String?
    private var itemObservers: [NSKeyValueObservation] = []
    private var notificationTokens: [NSObjectProtocol] = []
    private var timeControlObserver: NSKeyValueObservation?
    private var stallTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var healthyTask: Task<Void, Never>?
    private static let maxRetries = 3
    /// `AVError.fileFormatNotRecognized`: the file is reachable but this player can't read it (MKV, AVI).
    private static let unsupportedFormatCode = -11828
    /// The same error as a CoreMedia code: the four characters `fmt?`, seen for MP2 audio on tvOS.
    private static let coreMediaFormatCode = 0x666D_743F
    private static let defaultUserAgent = "Tuner/0.1 (open-source IPTV player)"

    /// Created on first use: most providers never need it.
    private var vlcPlayer: VLCMediaPlayer?
    private lazy var vlcEvents = VLCEvents(engine: self)
    /// VLC draws into this one view, which moves to whichever surface is on top: VLCKit 3
    /// can't move its picture to another drawable while playing.
    private lazy var vlcView: UIView = {
        let v = UIView()
        v.backgroundColor = .black
        v.isUserInteractionEnabled = false
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return v
    }()
    /// Video surfaces on screen, oldest first; the newest one shows VLC's picture.
    private var surfaces: [PlayerLayerView] = []
    /// Streams AVPlayer couldn't decode this session, which go straight to VLC when played again.
    private var vlcURLs = Set<String>()
    private var vlcMonitor: Task<Void, Never>?
    private var vlcLastProgress = Date()
    private var vlcStarted = false
    private var audioLanguage = ""
    private var subtitleLanguage = ""

    init() {
        #if os(iOS)
        // Otherwise the phone's silent switch mutes the picture's sound.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        player.automaticallyWaitsToMinimizeStalling = true
        timeControlObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            Task { @MainActor in
                guard let self else { return }
                self.isBuffering = self.isStalled
                self.noteIfPlaying()
                self.watchStall()
            }
        }
    }

    func play(_ request: PlaybackRequest) {
        if current?.url == request.url, isLoaded, !gaveUp {
            current = request
            wantsPlayback = true
            if backend == .vlc { vlcPlayer?.play() } else { player.play() }
            return
        }
        cancelRetries()
        gaveUp = false
        current = request
        retries = 0
        status = nil
        wantsPlayback = true
        backend = vlcURLs.contains(request.url) ? .vlc : .system
        activeURL = backend == .vlc ? request.url : Self.preferredURL(request.url)
        prepare(request, startMs: request.startPositionMs)
    }

    private var isLoaded: Bool {
        switch backend {
        case .system: return player.currentItem != nil && player.currentItem?.status != .failed
        case .vlc: return vlcPlayer?.media != nil && vlcPlayer?.state != .error
        }
    }

    var isPlaying: Bool {
        backend == .vlc ? vlcPlayer?.isPlaying == true : player.timeControlStatus == .playing
    }

    /// AVPlayer can't play raw MPEG-TS. Xtream-style panels serve the same stream as HLS
    /// when the extension is swapped, so try that form first for live `.ts` paths.
    static func preferredURL(_ url: String) -> String {
        guard url.range(of: #"/live/[^/]+/[^/]+/\d+\.ts(\?.*)?$"#, options: .regularExpression) != nil else { return url }
        return hlsVariant(url) ?? url
    }

    private static func hlsVariant(_ url: String) -> String? {
        let path = url.components(separatedBy: "?")[0]
        guard path.lowercased().hasSuffix(".ts") else { return nil }
        return String(path.dropLast(3)) + ".m3u8" + (url.contains("?") ? "?" + url.components(separatedBy: "?").dropFirst().joined(separator: "?") : "")
    }

    /// Stops playback after `minutes`; 0 turns the timer off. It keeps running across channel changes.
    func setSleepTimer(minutes: Int) {
        sleepTask?.cancel()
        guard minutes > 0 else { sleepAt = nil; return }
        sleepAt = Date().addingTimeInterval(TimeInterval(minutes * 60))
        sleepTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60_000_000_000)
            guard !Task.isCancelled else { return }
            self.sleepAt = nil
            self.stop()
            self.sleepFired += 1
        }
    }

    /// Preferred audio and subtitle languages (ISO 639-1; empty = stream default / no subtitles).
    /// AVPlayer applies them to every item it loads from now on.
    func setLanguages(audio: String, subtitles: String) {
        audioLanguage = audio
        subtitleLanguage = subtitles
        player.appliesMediaSelectionCriteriaAutomatically = true
        player.setMediaSelectionCriteria(
            audio.isEmpty ? nil : AVPlayerMediaSelectionCriteria(preferredLanguages: [audio], preferredMediaCharacteristics: nil),
            forMediaCharacteristic: .audible)
        player.setMediaSelectionCriteria(
            subtitles.isEmpty ? nil : AVPlayerMediaSelectionCriteria(preferredLanguages: [subtitles], preferredMediaCharacteristics: nil),
            forMediaCharacteristic: .legible)
    }

    func togglePause() {
        if backend == .vlc, let vlc = vlcPlayer {
            wantsPlayback = !vlc.isPlaying
            if wantsPlayback { vlc.play() } else { vlc.pause() }
            return
        }
        if player.rate == 0 {
            wantsPlayback = true
            player.play()
        } else {
            wantsPlayback = false
            player.pause()
        }
    }

    /// Skips within a movie or episode playing in VLC (the system player has its own scrubbing).
    func skip(seconds: Int32) {
        guard backend == .vlc, let vlc = vlcPlayer, current?.isLive == false else { return }
        if seconds > 0 { vlc.jumpForward(seconds) } else { vlc.jumpBackward(-seconds) }
    }

    func stop() {
        activeURL = nil
        gaveUp = false
        cancelRetries()
        clearItemObservers()
        current = nil
        status = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        stopVLC()
        backend = .system
    }

    private func prepare(_ request: PlaybackRequest, startMs: Int64) {
        if backend == .vlc {
            prepareVLC(request, startMs: startMs)
            return
        }
        stopVLC()
        guard let url = Self.url(from: activeURL ?? request.url) else {
            status = "Invalid stream address"
            return
        }
        var headers = ["User-Agent": request.userAgent ?? Self.defaultUserAgent]
        if let ref = request.referrer { headers["Referer"] = ref }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = request.isLive ? 6 : 0
        observe(item, request: request)
        // Live IPTV: start as soon as data arrives instead of waiting for AVPlayer's
        // stall prediction, which is often too pessimistic for single-variant live HLS.
        player.automaticallyWaitsToMinimizeStalling = !request.isLive
        player.replaceCurrentItem(with: item)
        if startMs > 0 {
            player.seek(to: CMTime(value: startMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .positiveInfinity)
        }
        if request.isLive { player.playImmediately(atRate: 1) } else { player.play() }
        // A retry can leave the status unchanged (still stalled), so don't rely on the observer to re-arm.
        watchStall()
    }

    private func observe(_ item: AVPlayerItem, request: PlaybackRequest) {
        clearItemObservers()
        // With automaticallyWaitsToMinimizeStalling off (live), AVPlayer drops to rate 0 on a
        // stall instead of waiting, so resume as soon as the buffer can keep up again.
        itemObservers.append(item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.wantsPlayback, item.isPlaybackLikelyToKeepUp, self.player.rate == 0,
                      self.player.currentItem === item else { return }
                if self.current?.isLive == true { self.player.playImmediately(atRate: 1) } else { self.player.play() }
            }
        })
        itemObservers.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                if item.status == .failed { self?.scheduleRetry(item.error, fallback: "Playback failed") }
                if item.status == .readyToPlay { self?.noteIfPlaying() }
            }
        })
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            Task { @MainActor in self?.scheduleRetry(err, fallback: "Stream interrupted") }
        })
        notificationTokens.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                if request.isLive { self?.scheduleRetry(nil, fallback: "Stream ended") } else { self?.finished += 1 }
            }
        })
    }

    private func clearItemObservers() {
        itemObservers.forEach { $0.invalidate() }
        itemObservers.removeAll()
        notificationTokens.forEach(NotificationCenter.default.removeObserver)
        notificationTokens.removeAll()
    }

    private func scheduleRetry(_ error: Error?, fallback: String) {
        guard let request = current, retryTask == nil else { return }
        stallTask?.cancel()
        NSLog("[Tuner] playback problem: %@", error?.localizedDescription ?? fallback)
        if backend == .system, let error, Self.isUnsupportedFormat(error) {
            switchToVLC(request)
            return
        }
        // A raw .ts stream that AVPlayer rejected: try the panel's HLS variant once before backing off.
        if backend == .system, retries == 0, let current = activeURL, let hls = Self.hlsVariant(current), hls != current {
            activeURL = hls
            prepare(request, startMs: 0)
            retries = 1
            return
        }
        guard retries < Self.maxRetries else {
            giveUp(request.isLive ? "This channel isn't available right now." : "This video isn't available right now.")
            return
        }
        let delay = UInt64(1 << retries) * 1_000_000_000
        retries += 1
        status = "Reconnecting… (\(retries)/\(Self.maxRetries))"
        retryTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, self.current === request else { return }
            self.retryTask = nil
            let position = request.isLive ? 0 : self.positionMs
            self.prepare(request, startMs: max(0, position))
        }
    }

    /// AVPlayer reports `.playing` as soon as it is asked to play, even for a stream it hasn't reached,
    /// so only a ready item clears the message, and only sustained playback earns back the retries.
    /// Otherwise a stream that keeps failing would never run out of them.
    private func noteIfPlaying() {
        guard backend == .system else { return }
        healthyTask?.cancel()
        guard player.timeControlStatus == .playing, player.currentItem?.status == .readyToPlay else { return }
        status = nil
        healthyTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled else { return }
            self.retries = 0
        }
    }

    /// Stops trying and leaves the reason on screen; choosing the channel again starts afresh.
    private func giveUp(_ message: String) {
        cancelRetries()
        clearItemObservers()
        gaveUp = true
        wantsPlayback = false
        status = message
        isBuffering = false
        player.pause()
        vlcMonitor?.cancel()
        vlcPlayer?.stop()
    }

    private func cancelRetries() {
        stallTask?.cancel()
        healthyTask?.cancel()
        retryTask?.cancel()
        retryTask = nil
    }

    private static func isUnsupportedFormat(_ error: Error) -> Bool {
        let e = error as NSError
        if e.domain == AVFoundationErrorDomain, e.code == unsupportedFormatCode { return true }
        if e.domain == "CoreMediaErrorDomain", e.code == coreMediaFormatCode { return true }
        return (e.userInfo[NSUnderlyingErrorKey] as? Error).map(isUnsupportedFormat) ?? false
    }

    /// Waiting for data, or, for live (which doesn't wait to minimise stalls), dropped to paused
    /// although the user didn't pause: a stream that stopped delivering segments ends up there.
    private var isStalled: Bool {
        switch player.timeControlStatus {
        case .waitingToPlayAtSpecifiedRate: return true
        case .paused: return current?.isLive == true && wantsPlayback && player.currentItem != nil
        default: return false
        }
    }

    /// Treats more than 10 s of continuous stalling on a live stream as a failure.
    private func watchStall() {
        stallTask?.cancel()
        guard isStalled, current?.isLive == true else { return }
        stallTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled, self.isStalled else { return }
            self.scheduleRetry(nil, fallback: "Stream stalled")
        }
    }

    #if DEBUG
    /// One-line player diagnostics for the `-playerHUD` launch argument.
    var debugSummary: String {
        if backend == .vlc, let vlc = vlcPlayer {
            return "vlc state=\(VLCMediaPlayerStateToString(vlc.state)) playing=\(vlc.isPlaying) t=\(vlc.time.intValue) audio=\(vlc.numberOfAudioTracks) video=\(Int(vlc.videoSize.width))x\(Int(vlc.videoSize.height))"
        }
        let item = player.currentItem
        let loaded = item?.loadedTimeRanges.map { String(format: "%.1f+%.1f", $0.timeRangeValue.start.seconds, $0.timeRangeValue.duration.seconds) }.joined(separator: ",") ?? "-"
        let wait = player.reasonForWaitingToPlay?.rawValue.replacingOccurrences(of: "AVPlayerWaiting", with: "") ?? "-"
        let access = item?.accessLog()?.events.last.map { "seg=\($0.numberOfMediaRequests) stalls=\($0.numberOfStalls) bitrate=\(Int($0.indicatedBitrate))" } ?? "-"
        let err = item?.errorLog()?.events.last.map { "err \($0.errorStatusCode) \($0.errorDomain): \($0.errorComment ?? "")" } ?? ""
        return "status=\(item?.status.rawValue ?? -1) tcs=\(player.timeControlStatus.rawValue) wait=\(wait) t=\(String(format: "%.1f", player.currentTime().seconds)) loaded=\(loaded) \(access) \(err) \(item?.error?.localizedDescription ?? "")"
    }
    #endif

    var positionMs: Int64 {
        if backend == .vlc { return Int64(vlcPlayer?.time.intValue ?? 0) }
        let t = player.currentTime().seconds
        return t.isFinite ? Int64(t * 1000) : 0
    }

    var durationMs: Int64 {
        if backend == .vlc { return Int64(vlcPlayer?.media?.length.intValue ?? 0) }
        guard let d = player.currentItem?.duration.seconds, d.isFinite else { return 0 }
        return Int64(d * 1000)
    }

    private static func url(from string: String) -> URL? {
        if let u = URL(string: string) { return u }
        return string.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed).flatMap(URL.init(string:))
    }
}

// MARK: - VLC fallback

extension PlayerEngine {
    /// The current stream couldn't be decoded by AVPlayer: play it in VLC from now on.
    private func switchToVLC(_ request: PlaybackRequest) {
        NSLog("[Tuner] AVPlayer can't decode this stream; using VLC")
        vlcURLs.insert(request.url)
        clearItemObservers()
        player.replaceCurrentItem(with: nil)
        backend = .vlc
        activeURL = request.url
        retries = 0
        status = nil
        prepare(request, startMs: request.isLive ? 0 : request.startPositionMs)
    }

    private func prepareVLC(_ request: PlaybackRequest, startMs: Int64) {
        clearItemObservers()
        player.pause()
        player.replaceCurrentItem(with: nil)
        guard let url = Self.url(from: activeURL ?? request.url) else {
            status = "Invalid stream address"
            return
        }
        let vlc = vlcPlayer ?? makeVLC()
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(request.userAgent ?? Self.defaultUserAgent)")
        if let ref = request.referrer { media.addOption(":http-referrer=\(ref)") }
        media.addOption(":network-caching=\(request.isLive ? 2000 : 1500)")
        if startMs > 0 { media.addOption(":start-time=\(Double(startMs) / 1000)") }
        if !audioLanguage.isEmpty { media.addOption(":audio-language=\(audioLanguage)") }
        if !subtitleLanguage.isEmpty { media.addOption(":sub-language=\(subtitleLanguage)") }
        vlc.media = media
        vlcStarted = false
        vlcLastProgress = Date()
        isBuffering = true
        vlc.play()
        monitorVLC()
    }

    private func makeVLC() -> VLCMediaPlayer {
        let vlc = VLCMediaPlayer()
        vlc.drawable = vlcView
        vlc.delegate = vlcEvents
        vlcPlayer = vlc
        return vlc
    }

    private func stopVLC() {
        vlcMonitor?.cancel()
        vlcMonitor = nil
        guard let vlc = vlcPlayer, vlc.media != nil else { return }
        vlc.stop()
        vlc.media = nil
    }

    /// VLC reports buffering states too often to trust, so progress of the clock decides:
    /// no progress for a moment shows the spinner, and for 10 s on a live stream counts as a stall.
    private func monitorVLC() {
        vlcMonitor?.cancel()
        vlcMonitor = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled, self.backend == .vlc, !self.gaveUp else { return }
                let idle = Date().timeIntervalSince(self.vlcLastProgress)
                let waiting = self.wantsPlayback && idle > 1.5
                if self.isBuffering != waiting { self.isBuffering = waiting }
                if waiting, idle > 10, self.current?.isLive == true || !self.vlcStarted {
                    self.scheduleRetry(nil, fallback: "Stream stalled")
                    return
                }
            }
        }
    }

    fileprivate func vlcStateChanged() {
        guard backend == .vlc, let vlc = vlcPlayer, let request = current else { return }
        switch vlc.state {
        case .error:
            scheduleRetry(nil, fallback: "Playback failed")
        case .ended:
            if request.isLive { scheduleRetry(nil, fallback: "Stream ended") } else { finished += 1 }
        default:
            break
        }
    }

    fileprivate func vlcTimeChanged() {
        guard backend == .vlc else { return }
        vlcLastProgress = Date()
        if isBuffering { isBuffering = false }
        guard !vlcStarted else { return }
        vlcStarted = true
        status = nil
        // As with AVPlayer, only sustained playback earns back the retries.
        healthyTask?.cancel()
        healthyTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled else { return }
            self.retries = 0
        }
    }

    // MARK: Surfaces

    func attach(_ surface: PlayerLayerView) {
        surfaces.removeAll { $0 === surface }
        surfaces.append(surface)
        placeVLC()
    }

    func detach(_ surface: PlayerLayerView) {
        surfaces.removeAll { $0 === surface }
        placeVLC()
    }

    /// Puts VLC's picture in the newest surface, or takes it down when AVPlayer is playing.
    func placeVLC() {
        guard backend == .vlc, let host = surfaces.last else {
            if vlcPlayer != nil { vlcView.removeFromSuperview() }
            return
        }
        if vlcView.superview !== host {
            vlcView.frame = host.bounds
            host.addSubview(vlcView)
        }
        applyFill(host.fill, size: host.bounds.size)
    }

    /// "Fill" crops the picture to the surface's shape, like `resizeAspectFill`.
    private func applyFill(_ fill: Bool, size: CGSize) {
        guard let vlc = vlcPlayer else { return }
        guard fill, size.width > 0, size.height > 0 else {
            vlc.videoCropGeometry = nil
            return
        }
        let geometry = strdup("\(Int(size.width.rounded())):\(Int(size.height.rounded()))")
        vlc.videoCropGeometry = geometry
        free(geometry)
    }

    // MARK: Tracks

    struct VLCTrack: Identifiable {
        let id: Int32
        let name: String
    }

    /// Audio and subtitle tracks of a stream playing in VLC ("Disable" included for subtitles).
    var vlcTracks: (audio: [VLCTrack], subtitles: [VLCTrack]) {
        guard backend == .vlc, let vlc = vlcPlayer else { return ([], []) }
        func tracks(_ names: [Any]?, _ ids: [Any]?) -> [VLCTrack] {
            zip(ids ?? [], names ?? []).compactMap { id, name in
                guard let id = (id as? NSNumber)?.int32Value, let name = name as? String else { return nil }
                return VLCTrack(id: id, name: name)
            }
        }
        return (tracks(vlc.audioTrackNames, vlc.audioTrackIndexes), tracks(vlc.videoSubTitlesNames, vlc.videoSubTitlesIndexes))
    }

    func selectVLCAudio(_ id: Int32) { vlcPlayer?.currentAudioTrackIndex = id }
    func selectVLCSubtitles(_ id: Int32) { vlcPlayer?.currentVideoSubTitleIndex = id }
}

/// Forwards VLC's delegate callbacks, which arrive on the main thread, to the engine.
private final class VLCEvents: NSObject, VLCMediaPlayerDelegate {
    weak var engine: PlayerEngine?

    init(engine: PlayerEngine) { self.engine = engine }

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        MainActor.assumeIsolated { engine?.vlcStateChanged() }
    }

    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        MainActor.assumeIsolated { engine?.vlcTimeChanged() }
    }
}
