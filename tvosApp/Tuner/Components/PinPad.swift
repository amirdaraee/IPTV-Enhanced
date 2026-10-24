import SwiftUI
import TunerCore

/// Four-digit PIN entry. `onSubmit` returns false for a wrong PIN.
struct PinPad: View {
    let title: String
    let message: String
    let onSubmit: (String) -> Bool
    let onCancel: () -> Void

    @EnvironmentObject private var model: AppModel
    @State private var pin = ""
    @State private var error = false
    @State private var lockoutSecs: Int64 = 0
    @FocusState private var firstKey: Bool

    var body: some View {
        VStack(spacing: 24) {
            Text(title).font(.title2.weight(.semibold))
            Text(lockoutSecs > 0 ? "Too many wrong attempts. Try again in \(lockoutSecs) s" : (error ? "Wrong PIN, try again" : message))
                .foregroundStyle(error || lockoutSecs > 0 ? Palette.live : Palette.textDim)
            HStack(spacing: 18) {
                ForEach(0..<4, id: \.self) { i in
                    Circle().fill(i < pin.count ? Palette.accent : Palette.outline).frame(width: 22, height: 22)
                }
            }
            VStack(spacing: 14) {
                ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["⌫", "0", "✕"]], id: \.self) { row in
                    HStack(spacing: 14) {
                        ForEach(row, id: \.self) { key in
                            Button { press(key) } label: {
                                Text(key).font(.title2).frame(width: 110, height: 70)
                            }
                            .focused($firstKey, equals: key == "1")
                        }
                    }
                }
            }
        }
        .padding(60)
        .background(Palette.surfaceHigh, in: RoundedRectangle(cornerRadius: 28))
        .onAppear {
            firstKey = true
            refreshLockout()
        }
        .onExitCommand(perform: onCancel)
        .task(id: lockoutSecs) {
            guard lockoutSecs > 0 else { return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            refreshLockout()
        }
    }

    private func refreshLockout() {
        lockoutSecs = (model.core.pinLockoutRemainingMs() + 999) / 1000
    }

    private func press(_ key: String) {
        switch key {
        case "⌫": pin = String(pin.dropLast())
        case "✕": onCancel()
        default:
            guard pin.count < 4 else { return }
            pin += key
            error = false
            if pin.count == 4 {
                if !onSubmit(pin) {
                    error = true
                    pin = ""
                    refreshLockout()
                }
            }
        }
    }
}

/// Runs `action` immediately if allowed, otherwise asks for the PIN first.
@MainActor
final class PinGate: ObservableObject {
    @Published var pending: (title: String, action: () -> Void)?

    func run(_ core: Tuner, locked: Bool, title: String, action: @escaping () -> Void) {
        if core.isAllowed(locked: locked) { action() } else { pending = (title, action) }
    }
}

struct PinGateOverlay: ViewModifier {
    @ObservedObject var gate: PinGate
    @EnvironmentObject private var model: AppModel

    func body(content: Content) -> some View {
        content.fullScreenCover(isPresented: Binding(get: { gate.pending != nil }, set: { if !$0 { gate.pending = nil } })) {
            ZStack {
                Color.black.opacity(0.6).ignoresSafeArea()
                if let p = gate.pending {
                    PinPad(title: "Parental PIN", message: "Enter the PIN to open \"\(p.title)\"", onSubmit: { pin in
                        guard model.core.unlock(pin: pin) else { return false }
                        let action = p.action
                        gate.pending = nil
                        DispatchQueue.main.async(execute: action)
                        return true
                    }, onCancel: { gate.pending = nil })
                }
            }
        }
    }
}

extension View {
    func pinGate(_ gate: PinGate) -> some View { modifier(PinGateOverlay(gate: gate)) }
}
