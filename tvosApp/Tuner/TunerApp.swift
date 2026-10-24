import SwiftUI
import TunerCore

@main
struct TunerApp: App {
    var body: some Scene {
        WindowGroup {
            Text("Tuner on \(PlatformKt.platformName)")
        }
    }
}
