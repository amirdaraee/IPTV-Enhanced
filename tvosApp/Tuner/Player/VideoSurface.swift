import AVFoundation
import AVKit
import SwiftUI

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// Renders an AVPlayer without controls (guide preview and live fullscreen).
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        v.playerLayer.videoGravity = gravity
        return v
    }

    func updateUIView(_ uiView: PlayerLayerView, context: Context) {
        uiView.playerLayer.videoGravity = gravity
    }

    static func dismantleUIView(_ uiView: PlayerLayerView, coordinator: ()) {
        uiView.playerLayer.player = nil
    }
}

/// System player UI (scrubbing, audio and subtitle menus) for movies, episodes and catch-up.
struct SystemPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer
    let title: String
    let subtitle: String?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.appliesPreferredDisplayCriteriaAutomatically = true
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
    }
}
