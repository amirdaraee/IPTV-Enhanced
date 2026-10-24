import SwiftUI
import TunerCore

@main
struct TunerApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.player)
                .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                // Re-lock parental controls and release the provider connection.
                model.core.lockSession()
                model.player.stop()
            case .active:
                model.core.refreshStale()
            default:
                break
            }
        }
    }
}
