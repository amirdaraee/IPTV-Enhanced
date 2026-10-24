import XCTest

/// End-to-end flows driven with the Siri Remote against tools/fixture-server.
/// Run: SHOTS_DIR=/path xcodebuild test ... (screenshots are written there for review).
final class TunerUITests: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private var step = 0

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
    }

    private func shot(_ name: String) {
        step += 1
        let png = XCUIScreen.main.screenshot().pngRepresentation
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"] {
            let file = URL(fileURLWithPath: dir).appendingPathComponent(String(format: "%02d-%@.png", step, name))
            try? png.write(to: file)
        }
    }

    private func press(_ button: XCUIRemote.Button, _ times: Int = 1, wait: TimeInterval = 0.8) {
        for _ in 0..<times {
            remote.press(button)
            Thread.sleep(forTimeInterval: wait)
        }
    }

    func testGuideAndLivePlayback() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900"]
        app.launch()
        Thread.sleep(forTimeInterval: 8)
        shot("guide")
        press(.down)
        press(.select, wait: 4)
        shot("preview")
        press(.right, 2)
        shot("guide-moved-right")
        press(.left, 4)
        shot("guide-catchup-area")
        press(.right, 2)
        press(.select, wait: 1)
        press(.select, wait: 3)
        shot("fullscreen")
        press(.down, wait: 3)
        shot("zapped")
        press(.select, wait: 1.5)
        shot("channel-list")
        press(.menu, wait: 1)
        press(.menu, wait: 1)
        press(.menu, wait: 1.5)
        shot("back-to-guide")
        press(.menu, wait: 1.5)
        shot("groups-panel")
    }

    func testMoviesSeriesSearchSettings() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.up, wait: 1)
        press(.right, wait: 1.5)
        shot("movies-tab")
        press(.down, wait: 1)
        press(.right, wait: 1)
        shot("movies-grid")
        press(.select, wait: 2.5)
        shot("movie-detail")
        press(.select, wait: 6)
        shot("movie-playing")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        shot("movie-after-play")
        press(.menu, wait: 1.5)
        press(.up, wait: 1)
        press(.right, wait: 1.5)
        press(.down, wait: 1)
        press(.right, wait: 1)
        press(.select, wait: 3)
        shot("series-detail")
        press(.down, 2, wait: 1)
        press(.select, wait: 5)
        shot("episode-playing")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        press(.menu, wait: 1.5)
        press(.up, wait: 1)
        press(.right, wait: 1.5)
        shot("search-tab")
        press(.up, wait: 1)
        press(.right, wait: 1.5)
        shot("settings-tab")
    }

    func testDebugPlayback() {
        let urls = ProcessInfo.processInfo.environment["PLAY_URLS"]?.split(separator: " ").map(String.init)
            ?? ["https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
                "http://localhost:8900/movie/a/b/1.mp4",
                "http://localhost:8900/hls/1/live.m3u8"]
        for (i, url) in urls.enumerated() {
            app.launchArguments = ["-debugPlay", url, "-playerHUD"]
            app.launch()
            Thread.sleep(forTimeInterval: 10)
            shot("debug-\(i)")
            app.terminate()
        }
    }

    func testOnboardingForm() {
        app.launch()
        Thread.sleep(forTimeInterval: 3)
        shot("welcome")
        press(.select, wait: 2)
        shot("form")
        press(.down, wait: 1)
        press(.down, wait: 1)
        shot("server-focused")
        press(.select, wait: 2)
        app.typeText("http://localhost:8900")
        shot("keyboard")
        press(.menu, wait: 1.5)
        shot("after-keyboard")
    }
}
