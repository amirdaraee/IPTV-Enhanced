import SwiftUI
import UIKit

/// The screens are designed for a 1080p TV. iPhones and iPads draw the same layouts with the
/// sizes scaled by `k` (iOS text styles are already about half their tvOS size).
enum UI {
    /// When the last sideways swipe of the guide ended (the far future during one). SwiftUI buttons
    /// still fire when the finger lifts after a swipe, which is not a tap.
    static var lastSwipe = Date.distantPast
    static var justSwiped: Bool { Date().timeIntervalSince(lastSwipe) < 0.3 }

    #if os(tvOS)
    static let k: CGFloat = 1
    static let touch = false
    static let phone = false
    #else
    static let phone = UIDevice.current.userInterfaceIdiom == .phone
    static let k: CGFloat = phone ? 0.5 : 0.6
    static let touch = true
    #endif
}

extension Int { var ui: CGFloat { CGFloat(self) * UI.k } }
extension Double { var ui: CGFloat { CGFloat(self) * UI.k } }

#if !os(tvOS)
// The remote's commands don't exist on iOS; touch has its own controls where they matter.
enum MoveCommandDirection { case up, down, left, right }

extension View {
    func onExitCommand(perform: (() -> Void)?) -> some View { self }
    func onMoveCommand(perform: ((MoveCommandDirection) -> Void)?) -> some View { self }
    func onPlayPauseCommand(perform: (() -> Void)?) -> some View { self }
    func focusSection() -> some View { self }
}
#endif

extension View {
    /// Defaults for controls that tvOS styles by itself.
    @ViewBuilder
    func touchDefaults() -> some View {
        #if os(tvOS)
        self
        #else
        buttonStyle(.bordered)
            .tint(Palette.accent)
            .textFieldStyle(.roundedBorder)
            .dynamicTypeSize(UI.phone ? .xSmall : .large)
        #endif
    }

    /// Stops the screen dimming while something plays; a TV player doesn't need it.
    func keepsScreenOn() -> some View {
        #if os(tvOS)
        self
        #else
        onAppear { UIApplication.shared.isIdleTimerDisabled = true }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        #endif
    }

    /// Dragging sideways moves the guide through time, one slot per `slotWidth` dragged.
    func timeSwipe(enabled: Bool, slotWidth: CGFloat, onShift: @escaping (Int) -> Void) -> some View {
        #if os(tvOS)
        self
        #else
        background(TimeSwipe(enabled: enabled, slotWidth: slotWidth, onShift: onShift))
        #endif
    }
}

#if !os(tvOS)
/// A pan recognizer on the window, limited to this view's frame. A SwiftUI drag inside a scroll
/// view is cancelled as soon as the scroll view's own pan starts, even for a sideways move.
private struct TimeSwipe: UIViewRepresentable {
    let enabled: Bool
    let slotWidth: CGFloat
    let onShift: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> HostView {
        let v = HostView()
        v.isUserInteractionEnabled = false
        v.recognizer = context.coordinator.recognizer
        context.coordinator.host = v
        return v
    }

    func updateUIView(_ v: HostView, context: Context) {
        context.coordinator.slotWidth = slotWidth
        context.coordinator.onShift = onShift
        context.coordinator.recognizer.isEnabled = enabled
    }

    static func dismantleUIView(_ v: HostView, coordinator: Coordinator) {
        coordinator.recognizer.view?.removeGestureRecognizer(coordinator.recognizer)
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        weak var host: UIView?
        var slotWidth: CGFloat = 1
        var onShift: (Int) -> Void = { _ in }
        private var applied = 0
        lazy var recognizer: UIPanGestureRecognizer = {
            let r = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
            // Once it is a swipe, the programme under the finger must not also count as tapped.
            r.cancelsTouchesInView = true
            r.delegate = self
            return r
        }()

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        /// Starts only inside the grid and only for a mostly sideways move.
        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let host, host.window != nil else { return false }
            guard host.bounds.contains(g.location(in: host)) else { return false }
            let v = recognizer.velocity(in: host)
            return abs(v.x) > abs(v.y)
        }

        @objc private func pan(_ r: UIPanGestureRecognizer) {
            switch r.state {
            case .began:
                applied = 0
                UI.lastSwipe = .distantFuture
            case .changed:
                let slots = Int(-r.translation(in: r.view).x / slotWidth)
                if slots != applied {
                    onShift(slots - applied)
                    applied = slots
                }
            default:
                UI.lastSwipe = Date()
            }
        }
    }

    final class HostView: UIView {
        var recognizer: UIGestureRecognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let recognizer else { return }
            recognizer.view?.removeGestureRecognizer(recognizer)
            window?.addGestureRecognizer(recognizer)
        }
    }
}
#endif

/// Scrolls on touch screens, which can be shorter than the layout; on a TV the layout fits and a
/// scroll view would change how focus moves.
struct TouchScroll<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        if UI.touch { ScrollView { content() } } else { content() }
    }
}

/// iOS has no Back button: full-screen covers need a way out. Nothing on a TV, where Menu does it.
struct CloseButton: View {
    let action: () -> Void

    var body: some View {
        if UI.touch {
            Button(action: action) {
                Image(systemName: "xmark").font(.headline).frame(width: 44, height: 44).contentShape(Circle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .background(.black.opacity(0.55), in: Circle())
            .accessibilityLabel("Close")
            .padding(12)
        }
    }
}
