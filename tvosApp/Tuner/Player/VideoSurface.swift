import AVFoundation
import AVKit
import SwiftUI

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    /// Whether the picture fills the view (cropping) rather than fitting in it.
    var fill = false
    weak var engine: PlayerEngine?

    override func layoutSubviews() {
        super.layoutSubviews()
        engine?.placeVLC()
    }
}

/// Renders the engine's picture without controls (guide preview, live fullscreen, and movies
/// that only VLC can play): AVPlayer's layer, or VLC's view when the engine has switched.
struct VideoSurface: UIViewRepresentable {
    @ObservedObject var engine: PlayerEngine
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.backgroundColor = .black
        v.playerLayer.player = engine.player
        v.playerLayer.videoGravity = gravity
        v.fill = gravity == .resizeAspectFill
        v.engine = engine
        engine.attach(v)
        return v
    }

    func updateUIView(_ uiView: PlayerLayerView, context: Context) {
        uiView.playerLayer.videoGravity = gravity
        uiView.fill = gravity == .resizeAspectFill
        engine.placeVLC()
    }

    static func dismantleUIView(_ uiView: PlayerLayerView, coordinator: ()) {
        uiView.playerLayer.player = nil
        uiView.engine?.detach(uiView)
    }
}

/// System player UI (scrubbing, audio and subtitle menus) for movies, episodes and catch-up.
struct SystemPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer
    let title: String
    let subtitle: String?
    /// Extra menu in the player's transport bar (the sleep timer).
    var menu: UIMenu?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        #if os(tvOS)
        vc.appliesPreferredDisplayCriteriaAutomatically = true
        #endif
        update(vc)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        update(vc)
    }

    private func update(_ vc: AVPlayerViewController) {
        let titleItem = AVMutableMetadataItem()
        titleItem.identifier = .commonIdentifierTitle
        titleItem.value = title as NSString
        var items: [AVMetadataItem] = [titleItem]
        if let subtitle {
            let s = AVMutableMetadataItem()
            s.identifier = .iTunesMetadataTrackSubTitle
            s.value = subtitle as NSString
            items.append(s)
        }
        vc.player?.currentItem?.externalMetadata = items
        #if os(tvOS)
        vc.transportBarCustomMenuItems = menu.map { [$0] } ?? []
        #endif
    }
}
