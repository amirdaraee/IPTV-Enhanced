import XCTest

/// Walks the touch flows on iPhone and iPad against the fixture server's showcase account
/// (`python3 tools/fixture-server/server.py --showcase`). Set
/// TEST_RUNNER_TOUCH_SHOTS to a folder to keep a screenshot of each step.
final class TouchFlowTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
        app.launchArguments = ["-resetData", "-demoXtream", "http://localhost:8900", "-demoUser", "showcase", "-demoName", "My TV"]
        app.launch()
    }

    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["TOUCH_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(idiom)-\(name).png"))
    }

    private func tab(_ name: String) {
        let b = app.buttons[name].firstMatch
        XCTAssertTrue(b.waitForExistence(timeout: 20), "tab \(name)")
        b.tap()
    }

    func test1GuideAndLivePlayer() {
        let group = app.buttons.matching(NSPredicate(format: "label CONTAINS 'All channels'")).firstMatch
        XCTAssertTrue(group.waitForExistence(timeout: 30))
        group.tap()
        let channel = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Ledger'")).firstMatch
        XCTAssertTrue(channel.waitForExistence(timeout: 10))
        sleep(2)
        shot("1-guide")

        // Swiping sideways moves the guide through time.
        let ruler = app.staticTexts.matching(NSPredicate(format: "label MATCHES '[0-9]{2}:[03]0'"))
        let times = { ruler.allElementsBoundByIndex.map(\.label).joined(separator: " ") }
        let before = times()
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.7))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.7)))
        sleep(1)
        XCTAssertNotEqual(before, times(), "swipe should move the guide")
        shot("2-guide-swiped")
        XCTAssertTrue(app.staticTexts["Tap a programme to preview"].exists, "a swipe must not also tap a programme")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.7)).press(forDuration: 0.05, thenDragTo: start)
        sleep(1)

        // A tap on a programme selects and previews it; the header follows.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.62)).tap()
        sleep(6)
        shot("3-preview")

        // A tap on the preview goes fullscreen, where the buttons are.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.2)).tap()
        let channels = app.buttons["Channels"].firstMatch
        XCTAssertTrue(channels.waitForExistence(timeout: 10), "fullscreen player")
        shot("4-player")

        // The bar hides itself after a few seconds, so each step first makes sure of where it is.
        let picture = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let showBar = {
            for _ in 0..<3 where !channels.exists {
                picture.tap()
                _ = channels.waitForExistence(timeout: 2)
            }
            XCTAssertTrue(channels.exists, "tapping the picture shows the bar")
        }
        showBar()
        picture.tap()
        sleep(1)
        XCTAssertFalse(channels.exists, "tapping again hides it")
        showBar()

        // Swipe up changes channel.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.7)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.3)))
        sleep(4)
        shot("5-zapped")

        // Channel list, closed by tapping beside it.
        showBar()
        channels.tap()
        sleep(2)
        shot("6-channel-list")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).tap()
        sleep(1)

        // Options, then out through the close button.
        showBar()
        let options = app.buttons["Options"].firstMatch
        options.tap()
        let sleepTimer = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Sleep timer'")).firstMatch
        XCTAssertTrue(sleepTimer.waitForExistence(timeout: 3), "options menu")
        shot("7-options")
        let outside = app.otherElements["PopoverDismissRegion"].firstMatch
        XCTAssertTrue(outside.exists, "a tap outside closes the options")
        outside.tap()
        sleep(1)
        let close = app.buttons["Close"].firstMatch
        showBar()
        XCTAssertTrue(close.exists, "close button")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(group.waitForExistence(timeout: 8), "back at the guide")
    }

    func test2MoviesAndSeries() {
        tab("Movies")
        let recent = app.buttons["Recently added"].firstMatch
        XCTAssertTrue(recent.waitForExistence(timeout: 30))
        recent.tap()
        sleep(5)
        shot("8-movies")
        let poster = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Copper Sky'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 10))
        poster.tap()
        let play = app.buttons["Play"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 10), "movie page")
        let title = app.staticTexts["Copper Sky"].firstMatch
        XCTAssertGreaterThan(title.frame.minX, 0, "the page starts on screen")
        sleep(2)
        shot("9-movie")
        play.tap()
        sleep(6)
        shot("10-movie-player")
        let close = app.buttons["Close"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5), "player close button")
        close.tap()
        sleep(1)
        XCTAssertFalse(close.exists, "the player closed")
        XCTAssertTrue(play.waitForExistence(timeout: 8), "back on the movie page")
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(recent.waitForExistence(timeout: 8), "back at the grid")

        tab("Series")
        let top = app.buttons["Top Series"].firstMatch
        XCTAssertTrue(top.waitForExistence(timeout: 10))
        top.tap()
        sleep(4)
        let series = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Harbor Lights'")).firstMatch
        XCTAssertTrue(series.waitForExistence(timeout: 10))
        series.tap()
        XCTAssertTrue(app.buttons["Season 1"].firstMatch.waitForExistence(timeout: 10), "series page")
        sleep(2)
        shot("11-series")
    }

    func test3Search() {
        tab("Search")
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        field.tap()
        field.typeText("ledger")
        sleep(3)
        shot("12-search")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Ledger'")).firstMatch.waitForExistence(timeout: 5), "search result")
    }

    private func row(_ title: String) -> XCUIElement {
        let r = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
        // Well inside the screen: the phone's floating tab bar covers the middle of the bottom edge.
        let height = app.frame.height
        for _ in 0..<10 {
            if r.exists, r.isHittable, r.frame.midY > height * 0.2, r.frame.midY < height * 0.7 { break }
            if r.exists, r.frame.midY <= height * 0.2 { app.swipeDown(velocity: .slow) } else { app.swipeUp(velocity: .slow) }
            sleep(1) // A tap while the list still coasts only stops it.
        }
        XCTAssertTrue(r.isHittable, title)
        return r
    }

    private func typePin() { for d in ["1", "2", "3", "4"] { app.buttons[d].firstMatch.tap() } }

    func test5ForgottenPinAndCloudSwitch() {
        tab("Settings")
        sleep(2)
        row("Set a PIN").tap()
        XCTAssertTrue(app.buttons["1"].firstMatch.waitForExistence(timeout: 5), "new PIN pad")
        typePin()
        sleep(1)
        XCTAssertTrue(app.buttons["1"].firstMatch.waitForExistence(timeout: 5), "confirm PIN pad")
        typePin()
        sleep(2)

        row("Hidden groups").tap()
        let forgot = app.buttons["Forgot PIN?"].firstMatch
        XCTAssertTrue(forgot.waitForExistence(timeout: 5), "the PIN pad offers a reset")
        shot("20-pin-forgot")
        forgot.tap()
        let field = app.secureTextFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5), "reset form")
        field.tap()
        field.typeText("wrong")
        let remove = app.buttons["Remove PIN"].firstMatch
        remove.tap()
        XCTAssertTrue(app.staticTexts["That doesn't match any of your playlists"].waitForExistence(timeout: 3), "a wrong guess is refused")
        shot("21-pin-reset-wrong")
        field.tap()
        field.typeText("secret")
        remove.tap()
        sleep(2)
        XCTAssertFalse(field.exists, "the right password closes the form")
        XCTAssertFalse(app.buttons["1"].exists, "without asking for the PIN again")

        let close = app.buttons["Cancel"].firstMatch
        if close.exists { close.tap(); sleep(1) }
        let sync = row("Sync across your devices")
        XCTAssertTrue(sync.label.contains("On"), sync.label)
        sync.tap()
        let off = app.buttons["Turn off, keep the iCloud copy"].firstMatch
        XCTAssertTrue(off.waitForExistence(timeout: 5), "the switch explains itself")
        shot("22-cloud-off-dialog")
        off.tap()
        sleep(2)
        XCTAssertTrue(row("Sync across your devices").label.contains("Off"), "turned off")
        row("Sync across your devices").tap()
        sleep(2)
        XCTAssertTrue(row("Sync across your devices").label.contains("On"), "and back on")
    }

    func test4SettingsAndPin() {
        tab("Settings")
        sleep(2)
        // A tap in the middle of a row, away from its text.
        let timeout = row("Info bar timeout")
        let before = timeout.label
        timeout.tap()
        sleep(1)
        XCTAssertNotEqual(before, timeout.label, "a row responds to a tap anywhere on it")
        shot("13-settings")

        row("Add playlist").tap()
        XCTAssertTrue(app.staticTexts["Add a playlist"].firstMatch.waitForExistence(timeout: 5), "form")
        sleep(1)
        shot("14-add-playlist")
        let cancel = app.buttons["Cancel"].firstMatch
        for _ in 0..<4 where !cancel.isHittable { app.swipeUp() }
        cancel.tap()
        sleep(1)

        row("Licence").tap()
        let close = app.buttons["Close"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5), "licence close button")
        shot("15-licence")
        close.tap()
        sleep(1)
        XCTAssertFalse(close.exists, "the licence closed")

        // A PIN, chosen and confirmed.
        row("Set a PIN").tap()
        XCTAssertTrue(app.buttons["1"].firstMatch.waitForExistence(timeout: 5), "new PIN pad")
        shot("16-pin")
        typePin()
        sleep(1)
        XCTAssertTrue(app.buttons["1"].firstMatch.waitForExistence(timeout: 5), "confirm PIN pad")
        typePin()
        sleep(2)

        // After a restart the PIN guards the playlists, and the right PIN opens them.
        app.terminate()
        app.launchArguments = []
        app.launch()
        tab("Settings")
        sleep(2)
        row("Add playlist").tap()
        XCTAssertTrue(app.buttons["1"].firstMatch.waitForExistence(timeout: 5), "the PIN must guard Add playlist")
        shot("17-pin-gate")
        typePin()
        XCTAssertTrue(app.staticTexts["Add a playlist"].firstMatch.waitForExistence(timeout: 5), "form after the PIN")

        // The PIN outlives -resetData through the Keychain backup, so a run must take it away again.
        let cancelForm = app.buttons["Cancel"].firstMatch
        for _ in 0..<4 where !cancelForm.isHittable { app.swipeUp() }
        cancelForm.tap()
        sleep(1)
        row("Remove PIN").tap()
        sleep(1)
        XCTAssertTrue(row("Set a PIN").exists, "PIN removed")
    }
}
