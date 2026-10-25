import AVFoundation
import Combine
import TunerCore
import UIKit

/// Owns the single AVPlayer shared by the guide preview and the fullscreen players,
/// so going fullscreen never re-buffers. Adds request headers and retries live
/// streams with exponential backoff.
@MainActor
final class PlayerEngine: ObservableObject {
    let player = AVPlayer()

    @Published private(set) var current: PlaybackRequest?
    @Published private(set) var status: String?
    @Published private(set) var isBuffering = false
    /// When the sleep timer stops playback, or nil when it is off.
    @Published private(set) var sleepAt: Date?
    /// Increments each time the sleep timer fires, so open players can close.
    @Published private(set) var sleepFired = 0
    private var sleepTask: Task<Void, Never>?

    private var retries = 0
    /// False only while the user has paused; stalled live streams auto-resume otherwise.
    private var wantsPlayback = true
    /// URL actually being played; may differ from the request's when a .ts stream is swapped for HLS.
    private var activeURL: String?
    private var itemObservers: [NSKeyValueObservation] = []
    private var notificationTokens: [NSObjectProtocol] = []
    private var timeControlObserver: NSKeyValueObservation?
    private var stallTask: Task<Void, Never>?
    private static let maxRetries = 5
    private static let defaultUserAgent = "Tuner/0.1 (open-source IPTV player)"

    init() {
        player.automaticallyWaitsToMinimizeStalling = true
        timeControlObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            Task { @MainActor in
                guard let self else { return }
                self.isBuffering = p.timeControlStatus == .waitingToPlayAtSpecifiedRate
                if p.timeControlStatus == .playing {
                    self.retries = 0
                    self.status = nil
                }
                self.watchStall()
            }
        }
    }

    func play(_ request: PlaybackRequest) {
        if current?.url == request.url, player.currentItem != nil, player.currentItem?.status != .failed {
            current = request
            player.play()
            return
        }
        current = request
        retries = 0
        status = nil
        wantsPlayback = true
        activeURL = Self.preferredURL(request.url)
        prepare(request, startMs: request.startPositionMs)
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
        player.appliesMediaSelectionCriteriaAutomatically = true
        player.setMediaSelectionCriteria(
            audio.isEmpty ? nil : AVPlayerMediaSelectionCriteria(preferredLanguages: [audio], preferredMediaCharacteristics: nil),
            forMediaCharacteristic: .audible)
        player.setMediaSelectionCriteria(
            subtitles.isEmpty ? nil : AVPlayerMediaSelectionCriteria(preferredLanguages: [subtitles], preferredMediaCharacteristics: nil),
            forMediaCharacteristic: .legible)
    }

    func togglePause() {
        if player.rate == 0 {
            wantsPlayback = true
            player.play()
        } else {
            wantsPlayback = false
            player.pause()
        }
    }

    func stop() {
        activeURL = nil
        stallTask?.cancel()
        clearItemObservers()
        current = nil
        status = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    private func prepare(_ request: PlaybackRequest, startMs: Int64) {
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
                if item.status == .failed { self?.scheduleRetry(item.error?.localizedDescription ?? "Playback failed") }
            }
        })
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
            let err = (note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription
            Task { @MainActor in self?.scheduleRetry(err ?? "Stream interrupted") }
        })
        if request.isLive {
            notificationTokens.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.scheduleRetry("Stream ended") }
            })
        }
    }

    private func clearItemObservers() {
        itemObservers.forEach { $0.invalidate() }
        itemObservers.removeAll()
        notificationTokens.forEach(NotificationCenter.default.removeObserver)
        notificationTokens.removeAll()
    }

    private func scheduleRetry(_ reason: String) {
        guard let request = current else { return }
        NSLog("[Tuner] playback problem: %@", reason)
        // A raw .ts stream that AVPlayer rejected: try the panel's HLS variant once before backing off.
        if retries == 0, let current = activeURL, let hls = Self.hlsVariant(current), hls != current {
            activeURL = hls
            prepare(request, startMs: 0)
            retries = 1
            return
        }
        guard retries < Self.maxRetries else {
            status = "Playback failed: \(reason)"
            return
        }
        let delay = UInt64(1 << retries) * 1_000_000_000
        retries += 1
        status = "Reconnecting… (\(retries)/\(Self.maxRetries))"
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            guard self.current === request else { return }
            let position = request.isLive ? 0 : Int64(self.player.currentTime().seconds * 1000)
            self.prepare(request, startMs: max(0, position))
        }
    }

    /// Treats more than 10 s of continuous buffering on a live stream as a failure.
    private func watchStall() {
        stallTask?.cancel()
        guard isBuffering, current?.isLive == true else { return }
        stallTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled, self.isBuffering else { return }
            self.scheduleRetry("Stream stalled")
        }
    }

    #if DEBUG
    /// One-line player diagnostics for the `-playerHUD` launch argument.
    var debugSummary: String {
        let item = player.currentItem
        let loaded = item?.loadedTimeRanges.map { String(format: "%.1f+%.1f", $0.timeRangeValue.start.seconds, $0.timeRangeValue.duration.seconds) }.joined(separator: ",") ?? "-"
        let wait = player.reasonForWaitingToPlay?.rawValue.replacingOccurrences(of: "AVPlayerWaiting", with: "") ?? "-"
        let access = item?.accessLog()?.events.last.map { "seg=\($0.numberOfMediaRequests) stalls=\($0.numberOfStalls) bitrate=\(Int($0.indicatedBitrate))" } ?? "-"
        let err = item?.errorLog()?.events.last.map { "err \($0.errorStatusCode) \($0.errorDomain): \($0.errorComment ?? "")" } ?? ""
        return "status=\(item?.status.rawValue ?? -1) tcs=\(player.timeControlStatus.rawValue) wait=\(wait) t=\(String(format: "%.1f", player.currentTime().seconds)) loaded=\(loaded) \(access) \(err) \(item?.error?.localizedDescription ?? "")"
    }
    #endif

    var positionMs: Int64 {
        let t = player.currentTime().seconds
        return t.isFinite ? Int64(t * 1000) : 0
    }

    var durationMs: Int64 {
        guard let d = player.currentItem?.duration.seconds, d.isFinite else { return 0 }
        return Int64(d * 1000)
    }

    private static func url(from string: String) -> URL? {
        if let u = URL(string: string) { return u }
        return string.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed).flatMap(URL.init(string:))
    }
}
