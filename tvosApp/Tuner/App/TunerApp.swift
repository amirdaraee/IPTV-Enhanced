import SwiftUI
import TunerCore

@main
struct TunerApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.player)
                .preferredColorScheme(.dark)
        }
    }
}
