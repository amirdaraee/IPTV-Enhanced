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

    /// Types once the on-screen keyboard has focus; it can take a few seconds to appear.
    private func type(_ text: String) {
        let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasKeyboardFocus == true"))
        _ = focused.waitForExistence(timeout: 8)
        app.typeText(text)
    }

    func testGuideAndLivePlayback() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900", "-playerHUD"]
        app.launch()
        Thread.sleep(forTimeInterval: 8)
        shot("guide")
        press(.select, wait: 6)
        shot("preview")
        press(.select, wait: 4)
        shot("fullscreen")
        press(.down, wait: 5)
        shot("zapped")
        press(.select, wait: 1.5)
        shot("channel-list")
        press(.menu, wait: 1)
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        shot("back-to-guide")
        press(.right, 2)
        shot("guide-moved-right")
        press(.left, 6)
        shot("guide-catchup-area")
        press(.menu, wait: 1.5)
        shot("groups-panel")
        press(.down, wait: 1)
        press(.select, wait: 3)
        shot("sports-group")
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
            app.launchArguments = ["-debugPlay", url, "-playerHUD"] + (url.contains("live") ? ["-debugLive"] : [])
            app.launch()
            Thread.sleep(forTimeInterval: 10)
            shot("debug-\(i)")
            Thread.sleep(forTimeInterval: 3)
            shot("debug-\(i)-later")
            app.terminate()
        }
    }

    func testSeriesDetail() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.up, wait: 1)
        press(.right, 2, wait: 1.5)
        shot("series-tab")
        press(.down, wait: 1)
        press(.right, wait: 1)
        press(.select, wait: 4)
        shot("series-detail")
        press(.down, wait: 1)
        press(.down, wait: 1)
        shot("episode-focused")
    }

    func testSearch() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.up, wait: 1)
        press(.right, 3, wait: 1.5)
        press(.down, wait: 1.5)
        shot("search-focused")
        type("match")
        Thread.sleep(forTimeInterval: 1)
        shot("search-keyboard")
        press(.down, 4, wait: 0.6)
        Thread.sleep(forTimeInterval: 1.5)
        shot("search-results")
    }

    func testOnboardingForm() {
        app.launchArguments = ["-resetData"]
        app.launch()
        Thread.sleep(forTimeInterval: 3)
        shot("welcome")
        press(.select, wait: 2)
        shot("form")
        press(.down, wait: 1)     // name
        press(.down, wait: 1)     // server
        press(.select, wait: 2)
        type("http://localhost:8900")
        shot("server-keyboard")
        press(.menu, wait: 1.5)
        press(.down, wait: 1)     // username
        press(.select, wait: 2)
        type("demo")
        press(.menu, wait: 1.5)
        press(.right, wait: 1)    // password
        press(.select, wait: 2)
        type("secret")
        press(.menu, wait: 1.5)
        shot("form-filled")
        press(.down, 3, wait: 1)  // epg, user agent, buttons
        press(.left, wait: 1)     // "Add playlist"
        shot("add-focused")
        press(.select, wait: 10)
        shot("after-add")
    }

    func testSettings() {
        app.launchArguments = ["-demoXtream", "http://localhost:8900"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.up, wait: 1)
        press(.right, 4, wait: 1.5)
        press(.down, wait: 1.5)
        shot("settings")
        press(.down, 9, wait: 0.5)
        Thread.sleep(forTimeInterval: 1)
        shot("settings-parental")
    }

    /// Sets up a real account and adds its news channels to Favorites, then opens Favorites.
    /// Skipped unless LIVE_XTREAM="server|user|pass" is passed (TEST_RUNNER_LIVE_XTREAM via xcodebuild),
    /// so credentials never live in the repo.
    func testLiveAccountFavorites() throws {
        let live = ProcessInfo.processInfo.environment["LIVE_XTREAM"]?.split(separator: "|").map(String.init) ?? []
        try XCTSkipUnless(live.count == 3, "LIVE_XTREAM not set")
        app.launchArguments = ["-demoXtream", live[0], "-demoUser", live[1], "-demoPass", live[2], "-demoName", "Provider"]
        app.launch()
        // The app shows a spinner until the first sync of the account finishes; a large
        // provider takes minutes in a debug build.
        Thread.sleep(forTimeInterval: 5)
        let spinner = app.activityIndicators.firstMatch
        let done = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: spinner)
        wait(for: [done], timeout: 900)
        Thread.sleep(forTimeInterval: 3)
        shot("after-launch")
        press(.up, wait: 1)
        press(.right, 3, wait: 1.5)
        press(.down, wait: 1.5)
        type("news")
        Thread.sleep(forTimeInterval: 1)
        let addAll = app.buttons["Add all to Favorites"]
        for _ in 0..<40 where !addAll.exists { Thread.sleep(forTimeInterval: 5) }
        XCTAssertTrue(addAll.exists, "no channel results for 'news'")
        press(.down, wait: 1)
        for _ in 0..<6 where !addAll.hasFocus { press(.down, wait: 1) }
        for _ in 0..<6 where !addAll.hasFocus { press(.left, wait: 0.6) }
        shot("add-all-focused")
        XCTAssertTrue(addAll.hasFocus)
        press(.select, wait: 1)
        shot("added")
        let toast = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'Added' OR label == 'Already in Favorites'")).firstMatch
        XCTAssertTrue(toast.waitForExistence(timeout: 3))
    }
}
