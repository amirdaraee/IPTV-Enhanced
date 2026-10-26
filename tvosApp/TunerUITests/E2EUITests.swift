import XCTest

/// End-to-end and stress flows against tools/fixture-server. Each step writes a screenshot and a
/// line to log.txt (focused element + a few visible texts) into $SHOTS_DIR.
final class E2EUITests: XCTestCase {
    let remote = XCUIRemote.shared
    var app: XCUIApplication!
    var step = 0
    var prefix = ""
    var t0 = Date()

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        prefix = String(name.split(separator: " ").last?.dropLast() ?? "t")
        t0 = Date()
    }

    // MARK: helpers

    var dir: URL? { ProcessInfo.processInfo.environment["SHOTS_DIR"].map { URL(fileURLWithPath: $0) } }

    func log(_ s: String) {
        let line = String(format: "[%@ %6.1fs] ", prefix, Date().timeIntervalSince(t0)) + s + "\n"
        print(line, terminator: "")
        guard let dir else { return }
        let f = dir.appendingPathComponent("log.txt")
        if let h = try? FileHandle(forWritingTo: f) { h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); try? h.close() }
        else { try? line.data(using: .utf8)!.write(to: f) }
    }

    func shot(_ name: String) {
        step += 1
        let png = XCUIScreen.main.screenshot().pngRepresentation
        if let dir {
            try? png.write(to: dir.appendingPathComponent(String(format: "%@-%02d-%@.png", prefix, step, name)))
        }
        log("SHOT \(step) \(name) focus=[\(focused())]")
    }

    func focused() -> String {
        let els = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).allElementsBoundByIndex
        return els.map { "\($0.elementType.rawValue):\($0.label.prefix(60))" }.joined(separator: " | ")
    }

    func press(_ b: XCUIRemote.Button, _ times: Int = 1, wait: TimeInterval = 0.8) {
        for _ in 0..<times {
            remote.press(b)
            Thread.sleep(forTimeInterval: wait)
        }
    }

    func hold(_ wait: TimeInterval = 1.5) {
        remote.press(.select, forDuration: 1.2)
        Thread.sleep(forTimeInterval: wait)
    }

    func type(_ text: String) {
        let f = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasKeyboardFocus == true"))
        for _ in 0..<3 where !f.waitForExistence(timeout: 3) { press(.down, wait: 1) }
        app.typeText(text)
    }

    func text(_ s: String) -> Bool {
        app.staticTexts[s].exists || app.buttons[s].exists
    }

    func textContaining(_ s: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", s)).firstMatch
    }

    func check(_ cond: Bool, _ what: String) {
        log((cond ? "OK   " : "FAIL ") + what)
        XCTAssertTrue(cond, what)
    }

    /// Context-menu items are cells with an inner labelled element; dialogs and lists are buttons.
    /// Moves focus to the item whose label contains `label` and selects it.
    @discardableResult
    func pick(_ label: String, _ dir: XCUIRemote.Button = .down, wait: TimeInterval = 1.5) -> Bool {
        let cells = app.cells.allElementsBoundByIndex
        if !cells.isEmpty {
            let labels = cells.map { c in c.descendants(matching: .any).allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: "/") }
            if let t = labels.firstIndex(where: { $0.contains(label) }) {
                let f = cells.firstIndex { $0.hasFocus } ?? 0
                log("menu \(labels) pick '\(label)' \(f)->\(t)")
                let d = t - f
                press(d > 0 ? .down : .up, abs(d), wait: 0.5)
                press(.select, wait: wait)
                return true
            }
            log("menu \(labels) has no '\(label)'")
        }
        let ok = moveTo(label, with: dir, max: 14, wait: 0.5) || moveTo(label, with: dir == .down ? .up : .left, max: 14, wait: 0.5)
        if ok { press(.select, wait: wait) } else { log("FAIL pick: no '\(label)' (focus \(focused()))") }
        return ok
    }

    func menuItems() -> [String] {
        app.cells.allElementsBoundByIndex.map { c in c.descendants(matching: .any).allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: "/") }
    }

    func focusIs(_ label: String) -> Bool { focused().contains(label) }

    /// Moves focus with `button` until an element whose label contains `label` has focus.
    @discardableResult
    func moveTo(_ label: String, with button: XCUIRemote.Button, max: Int = 12, wait: TimeInterval = 0.6) -> Bool {
        for _ in 0..<max {
            if focusIs(label) { return true }
            press(button, wait: wait)
        }
        return focusIs(label)
    }

    func launch(_ args: [String], wait: TimeInterval = 7) {
        app.launchArguments = args
        app.launch()
        Thread.sleep(forTimeInterval: wait)
    }

    let demo = ["-demoXtream", "http://localhost:8900"]

    let tabs = ["Live TV", "Movies", "Series", "Search", "Settings"]
    func onTabBar() -> Bool { let f = focused(); return tabs.contains { f == "9:" + $0 } }

    /// Selects a tab from anywhere: Menu until the tab bar has focus (never Menu on the tab bar itself, which exits).
    func goToTab(_ name: String) {
        if app.state != .runningForeground { app.activate(); Thread.sleep(forTimeInterval: 3) }
        for _ in 0..<6 where !onTabBar() { press(.menu, wait: 1.2) }
        for _ in 0..<6 where !focusIs(name) { press(.right, wait: 0.8) }
        for _ in 0..<6 where !focusIs(name) { press(.left, wait: 0.8) }
        log("tab focus: \(focused())")
    }

    func deleteChars(_ n: Int) {
        for _ in 0..<n { app.typeText(XCUIKeyboardKey.delete.rawValue) }
    }

    func crashCheck(_ where_: String) {
        let state = app.state
        if state != .runningForeground { log("FAIL app state \(state.rawValue) at \(where_)") }
        XCTAssertEqual(state, .runningForeground, "app not running at \(where_)")
    }

    // MARK: 1. onboarding

    /// Moves focus onto `el` with Down (then Up) presses.
    @discardableResult
    func focusOn(_ el: XCUIElement, max: Int = 10) -> Bool {
        let lbl = el.exists ? String(el.label.prefix(24)) : ""
        if !lbl.isEmpty {
            for _ in 0..<max where !focusIs(lbl) {
                press(.down, wait: 0.5)
                if !focusIs(lbl) { press(.left, wait: 0.4) }
            }
            for _ in 0..<(max * 2) where !focusIs(lbl) {
                press(.up, wait: 0.5)
                if !focusIs(lbl) { press(.right, wait: 0.4) }
            }
            return focusIs(lbl)
        }
        for _ in 0..<max where !el.hasFocus {
            press(.down, wait: 0.5)
            if !el.hasFocus { press(.left, wait: 0.4) }
        }
        for _ in 0..<max where !el.hasFocus {
            press(.up, wait: 0.5)
            if !el.hasFocus { press(.right, wait: 0.4) }
        }
        return el.hasFocus
    }

    /// Focuses a text field, opens the keyboard, replaces its text and closes the keyboard.
    func fill(_ el: XCUIElement, _ text: String, clear: Int = 0) {
        let ok = focusOn(el)
        log("fill focus ok=\(ok) \(focused())")
        press(.select, wait: 2)
        if clear > 0 { deleteChars(clear) }
        type(text)
        press(.menu, wait: 1.5)
    }

    func test01Onboarding() {
        launch(["-resetData"], wait: 4)
        shot("welcome")
        check(text("Add your playlist"), "welcome shows Add your playlist")
        press(.select, wait: 2)
        shot("form")
        check(text("Add a playlist"), "form open")
        log("fields: \(app.textFields.count) secure: \(app.secureTextFields.count)")
        // Empty submit
        let add = app.buttons["Add playlist"]
        focusOn(add)
        press(.select, wait: 1.5)
        shot("empty-submit")
        check(textContaining("Enter the server").exists, "empty form error")
        fill(app.textFields.element(boundBy: 1), "http://localhost:8900")
        fill(app.textFields.element(boundBy: 2), "demo")
        fill(app.secureTextFields.element(boundBy: 0), "bad")
        shot("form-filled-bad")
        focusOn(add)
        let tb = Date()
        press(.select, wait: 0.5)
        let err = app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'password' OR label CONTAINS[c] 'auth' OR label CONTAINS[c] 'invalid' OR label CONTAINS[c] 'login'")).firstMatch
        _ = err.waitForExistence(timeout: 15)
        log(String(format: "bad password response %.1fs", Date().timeIntervalSince(tb)))
        Thread.sleep(forTimeInterval: 1)
        shot("bad-password")
        log("texts after bad: \(app.staticTexts.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.suffix(4))")
        check(text("Add playlist"), "buttons back after bad password")
        fill(app.secureTextFields.element(boundBy: 0), "secret", clear: 5)
        focusOn(add)
        let start = Date()
        press(.select, wait: 0.5)
        shot("syncing")
        let tab = app.buttons["Live TV"]
        _ = tab.waitForExistence(timeout: 90)
        log(String(format: "add+sync took %.1fs", Date().timeIntervalSince(start)))
        Thread.sleep(forTimeInterval: 3)
        shot("after-add")
        check(tab.exists, "main UI after add")
        log("focus after add: \(focused())")

        // Settings: add an M3U source
        goToTab("Settings")
        press(.down, wait: 1.5)
        shot("settings")
        let addRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Add playlist'")).firstMatch
        focusOn(addRow)
        press(.select, wait: 2)
        shot("add-form-2")
        focusOn(app.buttons["M3U playlist URL"])
        press(.select, wait: 1)
        shot("m3u-picked")
        fill(app.textFields.element(boundBy: 0), "Fixture M3U")
        fill(app.textFields.element(boundBy: 1), "http://localhost:8900/playlist.m3u")
        shot("m3u-filled")
        focusOn(app.buttons["Add playlist"])
        let s2 = Date()
        press(.select, wait: 1)
        for _ in 0..<60 where text("Add a playlist") { Thread.sleep(forTimeInterval: 1) }
        log(String(format: "m3u add took %.1fs", Date().timeIntervalSince(s2)))
        Thread.sleep(forTimeInterval: 2)
        shot("after-m3u")
        let m3uRow = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Fixture M3U'")).firstMatch
        check(m3uRow.exists, "M3U source listed")
        log("m3u row: \(m3uRow.label)")

        // Edit the M3U source: rename
        focusOn(m3uRow)
        press(.select, wait: 1.5)
        shot("source-menu")
        focusOn(app.buttons["Edit"])
        press(.select, wait: 2.5)
        shot("edit-form")
        check(text("Edit playlist"), "edit form opens from the source menu")
        if text("Edit playlist") {
            log("edit fields: \(app.textFields.allElementsBoundByIndex.map { $0.value as? String ?? "" })")
            fill(app.textFields.element(boundBy: 0), " 2")
            focusOn(app.buttons["Save"])
            press(.select, wait: 3)
            shot("after-edit")
            check(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Fixture M3U 2'")).firstMatch.exists, "rename saved")
        }
        // Delete it
        focusOn(m3uRow)
        press(.select, wait: 1.5)
        focusOn(app.buttons["Delete"])
        press(.select, wait: 1.5)
        shot("delete-confirm")
        focusOn(app.buttons["Delete"])
        press(.select, wait: 2)
        shot("after-delete")
        check(!m3uRow.exists, "M3U deleted")
        // Cancel on an Add form with typed text (should ask before discarding?)
        focusOn(addRow)
        press(.select, wait: 2)
        fill(app.textFields.element(boundBy: 0), "temp")
        press(.menu, wait: 2)
        shot("menu-on-dirty-form")
        log("after menu on dirty form: form open=\(text("Add a playlist")) texts=\(app.staticTexts.allElementsBoundByIndex.map(\.label).prefix(3))")
        if text("Add a playlist") { focusOn(app.buttons["Cancel"]); press(.select, wait: 2) }
        crashCheck("onboarding end")
    }

    // MARK: 2. guide navigation

    func test02GuideNavigation() {
        launch(demo)
        shot("open")
        log("open focus \(focused())")
        check(focused().contains("News") || focused().contains("Favorites") || focused().contains("Sports"), "opens focused on a group")
        press(.select, wait: 2)
        shot("channels")
        press(.right, wait: 1.5)
        shot("programme-now")
        press(.right, wait: 1)
        press(.right, wait: 1)
        shot("right-x3")
        press(.right, 4, wait: 0.8)
        shot("right-x7")
        press(.playPause, wait: 1.5)
        shot("playpause-now")
        press(.left, wait: 1)
        shot("left-from-now")
        press(.left, wait: 1)
        shot("left-again")
        press(.right, wait: 1)
        press(.menu, wait: 1.2)
        shot("menu-1")
        press(.menu, wait: 1.2)
        shot("menu-2")
        press(.menu, wait: 1.2)
        shot("menu-3")
        log("state after 3rd Menu (tab bar): \(app.state.rawValue)")
        app.activate()
        Thread.sleep(forTimeInterval: 3)
        shot("reactivated")
        log("focus after reactivate \(focused())")
        pick("News", .down, wait: 2)
        shot("back-in-group")
        // Preview: select on a programme, then fullscreen
        press(.right, wait: 1.2)
        let s = Date()
        press(.select, wait: 4)
        log(String(format: "preview select +4s"))
        shot("preview")
        press(.select, wait: 4)
        shot("fullscreen-from-preview")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        shot("back-from-fullscreen")
        _ = s
        // channel name select → fullscreen in one press
        press(.menu, wait: 1)
        press(.down, wait: 1)
        shot("ch-name-2")
        press(.select, wait: 5)
        shot("fullscreen-one-press")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        shot("guide-after")
        // Earlier programmes via context menu, then Left keeps going back
        press(.right, wait: 1.2)
        hold()
        shot("ctx-menu")
        pick("Earlier programmes", .down, wait: 2)
        shot("earlier-1")
        press(.left, wait: 1.5)
        shot("earlier-left-1")
        for i in 0..<6 { press(.left, wait: 1.2); if i % 2 == 1 { shot("earlier-left-\(i + 2)") } }
        hold()
        shot("ctx-menu-past")
        pick("Back to now", .down, wait: 2)
        shot("back-to-now")
        // catch-up: go left into past programme on a News channel and select
        press(.left, wait: 1.2)
        shot("past-programme")
        press(.select, wait: 6)
        shot("catchup-playing")
        press(.menu, wait: 1.5)
        shot("catchup-menu1")
        press(.menu, wait: 2)
        shot("catchup-closed")
        crashCheck("guide end")
    }

    // MARK: 3. live player

    /// Opens the first channel of the open group fullscreen from the group list.
    func openFirstChannelFullscreen(group: String? = nil) {
        if let group { moveTo(group, with: .down, max: 8) }
        press(.select, wait: 2)
        press(.select, wait: 5)
    }

    func test03LivePlayer() {
        launch(demo + ["-playerHUD"])
        pick("News", .down, wait: 2)
        log("guide channel focus \(focused())")
        let t = Date()
        press(.select, wait: 0.2)
        _ = textContaining("LIVE").waitForExistence(timeout: 10)
        log(String(format: "fullscreen open %.1fs", Date().timeIntervalSince(t)))
        Thread.sleep(forTimeInterval: 3)
        shot("fullscreen-info")
        Thread.sleep(forTimeInterval: 5)
        shot("info-timed-out")
        check(!textContaining("No programme information").exists, "info bar shows a programme")
        press(.down, wait: 4)
        shot("zap-down")
        press(.up, wait: 0.5)
        press(.up, wait: 4)
        shot("zap-up-2")
        press(.right, wait: 1)
        shot("right-info")
        // channel list
        press(.select, wait: 2)
        shot("channel-list")
        press(.down, 3, wait: 0.6)
        shot("list-down3")
        press(.select, wait: 4)
        shot("list-tuned")
        // options
        hold()
        shot("options")
        log("options: \(menuItems()) buttons: \(app.buttons.allElementsBoundByIndex.map(\.label).suffix(8))")
        pick("Favorites", wait: 2)
        hold()
        shot("options-after-fav")
        check(textContaining("Remove from Favorites").exists, "options now say Remove from Favorites")
        press(.menu, wait: 1.5)
        // stale after zapping away and back?
        press(.down, wait: 4)
        press(.up, wait: 4)
        shot("back-on-fav-channel")
        hold()
        shot("options-after-zap")
        check(textContaining("Remove from Favorites").exists, "favorite state kept after zapping away and back")
        press(.menu, wait: 1.5)
        // sleep timer
        hold()
        pick("Sleep timer", .down, wait: 2)
        shot("sleep-dialog")
        pick("15 minutes", .down, wait: 1.5)
        hold()
        shot("options-sleep-set")
        check(textContaining("min left").exists, "options show N min left")
        // previous channel
        pick("Previous channel", .down, wait: 5)
        shot("previous-channel")
        // picture fill
        hold()
        pick("Picture", .down, wait: 2)
        shot("picture-fill")
        // audio/subtitle tracks present?
        hold()
        log("options list: \(app.buttons.allElementsBoundByIndex.map(\.label))")
        press(.menu, wait: 1.5)
        // turn sleep timer off again
        hold()
        pick("Sleep timer", .down, wait: 2)
        pick("Off", .up, wait: 1.5)
        // Menu order: list open → menu closes list → menu hides info → menu exits
        press(.select, wait: 2)
        check(textContaining("Daily News").exists || app.scrollViews.count > 0, "list open")
        press(.menu, wait: 1)
        shot("menu-closes-list")
        press(.menu, wait: 1)
        shot("menu-hides-info")
        press(.menu, wait: 2.5)
        shot("menu-exits")
        log("guide focus after exit: \(focused())")
        // rapid zap stress
        press(.select, wait: 5)
        let z = Date()
        for _ in 0..<40 { remote.press(.down); Thread.sleep(forTimeInterval: 0.15) }
        log(String(format: "40 rapid zaps took %.1fs", Date().timeIntervalSince(z)))
        Thread.sleep(forTimeInterval: 5)
        shot("after-rapid-zap")
        crashCheck("rapid zap")
        for _ in 0..<15 { remote.press(.up); Thread.sleep(forTimeInterval: 0.05) }
        Thread.sleep(forTimeInterval: 5)
        shot("after-rapid-zap-up")
        // Menu spam
        for _ in 0..<3 { remote.press(.menu); Thread.sleep(forTimeInterval: 0.2) }
        Thread.sleep(forTimeInterval: 2)
        shot("after-menu-spam")
        crashCheck("live end")
    }

    // MARK: 4. guide context menus

    func test04GuideMenus() {
        launch(demo)
        moveTo("Sports", with: .down, max: 8)
        hold()
        shot("group-menu")
        pick("Add all to Favorites", .down, wait: 1)
        shot("group-add-all")
        check(textContaining("Added").exists || textContaining("Already").exists, "add all toast")
        // move group up
        hold()
        pick("Move up", .down, wait: 2)
        shot("group-moved-up")
        log("focus after move \(focused())")
        // Favorites group: move a channel down
        pick("Favorites", .up, wait: 2)
        shot("favorites")
        hold()
        shot("fav-ctx")
        pick("Move down", .down, wait: 2)
        shot("fav-moved-down")
        log("focus after fav move \(focused())")
        // Hide a channel
        press(.down, wait: 1)
        hold()
        let hidden = focused()
        pick("Hide channel", wait: 2)
        shot("channel-hidden")
        log("focus after hide \(focused()) (was \(hidden))")
        // Hide group Kids
        press(.menu, wait: 1)
        moveTo("Kids", with: .down, max: 8)
        hold()
        pick("Hide group", .down, wait: 2)
        shot("group-hidden")
        log("focus after hide group \(focused())")
        check(!app.buttons["Kids"].exists, "Kids hidden")
        // Recent group appears after watching
        pick("News", .up, wait: 2)
        press(.select, wait: 5)
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        press(.menu, wait: 1)
        shot("after-watch")
        check(textContaining("Recent").exists, "Recent group appears")
        // Play/Pause spam and long press everywhere
        for _ in 0..<10 { remote.press(.playPause); Thread.sleep(forTimeInterval: 0.1) }
        Thread.sleep(forTimeInterval: 1)
        shot("playpause-spam")
        crashCheck("menus end")
        // relaunch: persistence of fav order, hidden
        app.terminate()
        launch(demo)
        shot("relaunch")
        check(!app.buttons["Kids"].exists, "Kids still hidden after relaunch")
        pick("Favorites", .up, wait: 2)
        shot("favorites-after-relaunch")
    }

    // MARK: 5. movies & series

    func test05Movies() {
        launch(demo)
        goToTab("Movies")
        press(.down, wait: 1.5)
        shot("movies-cats")
        press(.right, wait: 1.5)
        shot("movies-grid")
        hold()
        shot("movie-ctx")
        pick("Favorites", .down, wait: 2)
        shot("movie-faved")
        press(.select, wait: 3)
        shot("movie-detail")
        check(textContaining("Director").exists, "movie details loaded")
        let t = Date()
        press(.select, wait: 8)
        log(String(format: "movie play +%.1fs", Date().timeIntervalSince(t)))
        shot("movie-playing")
        // skip ahead (right = +10 s in the system player)
        press(.right, 6, wait: 0.7)
        Thread.sleep(forTimeInterval: 4)
        shot("movie-skipped")
        // transport bar menu: swipe down shows info/menu
        press(.down, wait: 2)
        shot("movie-transport")
        press(.menu, wait: 1.5)
        shot("movie-menu1")
        press(.menu, wait: 3)
        shot("movie-after-exit")
        check(text("Resume"), "Resume offered on the detail page")
        press(.menu, wait: 2)
        shot("grid-after-detail")
        // Continue watching
        press(.left, wait: 1)
        pick("Continue watching", .up, wait: 2)
        shot("continue-watching")
        press(.right, wait: 1)
        press(.select, wait: 8)
        shot("resume-playing")
        press(.menu, wait: 1.5)
        press(.menu, wait: 3)
        shot("after-resume")
        // Back behaviour: grid → category list → tab bar → Live TV?
        press(.menu, wait: 1.5)
        shot("movies-menu-1")
        press(.menu, wait: 1.5)
        shot("movies-menu-2")
        crashCheck("after menu x2 in movies")
        press(.menu, wait: 2)
        shot("movies-menu-3")
        log("state after 3 menus: \(app.state.rawValue)")
    }

    func test06Series() {
        launch(demo)
        goToTab("Series")
        press(.down, wait: 1.5)
        press(.right, wait: 1.5)
        shot("series-grid")
        press(.select, wait: 3)
        shot("series-detail")
        press(.down, wait: 1)
        shot("season-row")
        press(.right, wait: 1)
        press(.select, wait: 1.5)
        shot("season-2")
        press(.left, wait: 1)
        press(.select, wait: 1.5)
        press(.down, wait: 1)
        shot("episode-1")
        press(.select, wait: 8)
        shot("episode-playing")
        // skip to near the end: 5 min episode → 28 x +10 s
        for _ in 0..<29 { remote.press(.right); Thread.sleep(forTimeInterval: 0.4) }
        Thread.sleep(forTimeInterval: 2)
        shot("episode-near-end")
        let card = textContaining("Up next")
        _ = card.waitForExistence(timeout: 40)
        shot("up-next")
        check(card.exists, "Up next card shown")
        log("up next focus \(focused())")
        // key spam during countdown
        for _ in 0..<6 { remote.press(.right); Thread.sleep(forTimeInterval: 0.1); remote.press(.left); Thread.sleep(forTimeInterval: 0.1) }
        shot("up-next-spam")
        pick("Play now", .left, wait: 6)
        shot("next-episode-playing")
        // near end again, then Cancel
        for _ in 0..<29 { remote.press(.right); Thread.sleep(forTimeInterval: 0.4) }
        _ = card.waitForExistence(timeout: 40)
        shot("up-next-2")
        pick("Cancel", .right, wait: 3)
        shot("after-cancel")
        log("after cancel focus \(focused())")
        // third time, Menu
        press(.down, wait: 1)
        log("ep focus \(focused())")
        press(.select, wait: 8)
        for _ in 0..<29 { remote.press(.right); Thread.sleep(forTimeInterval: 0.4) }
        _ = card.waitForExistence(timeout: 40)
        shot("up-next-3")
        press(.menu, wait: 3)
        shot("after-upnext-menu")
        log("after menu focus \(focused())")
        // let one countdown run out
        press(.select, wait: 8)
        for _ in 0..<29 { remote.press(.right); Thread.sleep(forTimeInterval: 0.4) }
        _ = card.waitForExistence(timeout: 40)
        Thread.sleep(forTimeInterval: 12)
        shot("countdown-expired")
        press(.menu, wait: 1.5)
        press(.menu, wait: 3)
        shot("series-after")
        crashCheck("series end")
    }

    // MARK: 6. search

    func test07Search() {
        launch(demo)
        goToTab("Search")
        press(.down, wait: 1.5)
        shot("search-empty")
        log("recent shown? \(textContaining("Recent searches").exists)")
        type("news")
        Thread.sleep(forTimeInterval: 2)
        shot("search-keyboard")
        press(.down, 4, wait: 0.6)
        Thread.sleep(forTimeInterval: 1)
        shot("search-results")
        log("focus results \(focused())")
        pick("Add all to Favorites", .left, wait: 1)
        shot("search-add-all")
        check(textContaining("Favorites").exists, "toast after add all")
        press(.right, wait: 1)
        press(.select, wait: 6)
        shot("search-play")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        shot("search-after-play")
        // clear query: go up into field
        press(.up, 6, wait: 0.6)
        log("focus up \(focused())")
        press(.select, wait: 2)
        deleteChars(6)
        Thread.sleep(forTimeInterval: 2)
        shot("query-cleared")
        press(.down, 4, wait: 0.6)
        shot("recent-searches")
        check(textContaining("Recent searches").exists, "recent searches when query empty")
        pick("news", .right, wait: 2.5)
        shot("chip-selected")
        check(textContaining("Channels").exists, "chip filled the query and shows results")
        // movie search
        press(.up, 6, wait: 0.5)
        press(.select, wait: 2)
        deleteChars(6)
        type("night")
        Thread.sleep(forTimeInterval: 2)
        press(.down, 4, wait: 0.6)
        shot("search-movie")
        // nonsense
        press(.up, 6, wait: 0.5)
        press(.select, wait: 2)
        deleteChars(6)
        type("zzzqqq")
        Thread.sleep(forTimeInterval: 2)
        press(.down, 4, wait: 0.6)
        shot("search-none")
        check(textContaining("No results").exists, "no results message")
        // clear recents
        press(.up, 6, wait: 0.5)
        press(.select, wait: 2)
        deleteChars(8)
        press(.down, 4, wait: 0.6)
        pick("Clear", .right, wait: 1.5)
        shot("recents-cleared")
        check(!textContaining("Recent searches").exists, "recents cleared")
        crashCheck("search end")
    }

    // MARK: 7. settings & persistence

    let settingRows = ["Keep past programmes", "Load future programmes", "Info bar timeout", "Start with last channel",
                       "Audio language", "Subtitles", "Play next episode automatically", "Prefer HLS for Xtream live"]

    /// Value text shown on a settings row (the row's button label contains title + value).
    func rowLabel(_ title: String) -> String {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch.label
    }

    func openSettings() {
        goToTab("Settings")
        press(.down, wait: 1.5)
    }

    func test08Settings() {
        launch(demo)
        openSettings()
        shot("settings-top")
        var before: [String: String] = [:]
        for r in settingRows { before[r] = rowLabel(r); log("row \(r): '\(before[r]!)'") }
        for r in settingRows {
            moveTo(r, with: .down, max: 14, wait: 0.5)
            press(.select, wait: 1)
            log("after select \(r): '\(rowLabel(r))'")
            check(rowLabel(r) != before[r], "\(r) changes on select")
        }
        shot("settings-changed")
        var after: [String: String] = [:]
        for r in settingRows { after[r] = rowLabel(r) }
        app.terminate()
        launch(demo, wait: 9)
        shot("relaunch")
        // Start with last channel is now On: relaunch should open the last channel fullscreen
        let fullscreen = !app.staticTexts["Groups"].isHittable
        log("relaunch focus \(focused()) fullscreen? \(fullscreen)")
        check(fullscreen, "Start with last channel opens the last channel fullscreen on relaunch")
        openSettings()
        for r in settingRows { check(rowLabel(r) == after[r], "\(r) persisted ('\(rowLabel(r))' vs '\(after[r]!)')") }
        shot("settings-after-relaunch")
        // Restore the original values
        for r in settingRows {
            moveTo(r, with: .down, max: 14, wait: 0.5)
            for _ in 0..<12 where rowLabel(r) != before[r] { press(.select, wait: 0.7) }
            check(rowLabel(r) == before[r], "\(r) restored")
        }
        // Rapid toggling spam
        moveTo("Info bar timeout", with: .up, max: 14, wait: 0.5)
        for _ in 0..<20 { remote.press(.select); Thread.sleep(forTimeInterval: 0.1) }
        Thread.sleep(forTimeInterval: 1)
        log("timeout after 20 rapid selects: \(rowLabel("Info bar timeout"))")
        for _ in 0..<6 where rowLabel("Info bar timeout") != before["Info bar timeout"] { press(.select, wait: 0.7) }
        // hidden groups/channels dialogs
        pick("Hidden groups", .down, wait: 1.5)
        shot("hidden-groups")
        log("hidden groups buttons: \(app.buttons.allElementsBoundByIndex.map(\.label).filter { $0.hasPrefix("Show") })")
        press(.menu, wait: 1.5)
        pick("Hidden channels", .down, wait: 1.5)
        shot("hidden-channels")
        log("hidden channel buttons: \(app.buttons.allElementsBoundByIndex.map(\.label).filter { $0.hasPrefix("Show") })")
        press(.menu, wait: 1.5)
        // Playlist status line (account expiry)
        log("playlist row: \(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Xtream'")).firstMatch.label)")
        crashCheck("settings end")
    }

    /// Enters a PIN made of 1s and 2s on the pad (focus starts on "1").
    func enterPin(_ pin: String) {
        for c in pin {
            if c == "1" { moveTo("1", with: .left, max: 2, wait: 0.4) } else { if !focusIs(":2") { press(.right, wait: 0.4) } }
            press(.select, wait: 0.5)
        }
        Thread.sleep(forTimeInterval: 1)
    }

    func test09Pin() {
        launch(demo)
        openSettings()
        pick("Set a PIN", .down, wait: 2)
        shot("new-pin")
        log("pin focus \(focused())")
        enterPin("1111")
        shot("confirm-pin")
        enterPin("1111")
        shot("pin-set")
        check(text("Change PIN") || textContaining("Change PIN").exists, "PIN set")
        // lock the News group
        goToTab("Live TV")
        press(.down, wait: 1.5)
        // Down from the tab bar lands in the channel column; Left reaches the groups.
        if focusIs(", ") { press(.left, wait: 1) } // a channel row ("1, DN, Daily News")
        moveTo("News", with: .down, max: 8)
        hold()
        shot("group-menu-pin")
        pick("Lock group", .down, wait: 2)
        shot("lock-group-gate")
        enterPin("1111")
        shot("group-locked")
        // lock a channel in Sports
        pick("Sports", .down, wait: 2)
        hold()
        pick("Lock channel", .down, wait: 2)
        log("lock channel gate visible? \(textContaining("Parental PIN").exists)")
        if textContaining("Parental PIN").exists { enterPin("1111") }
        shot("channel-locked")
        app.terminate()
        launch(demo)
        shot("relaunch-locked")
        log("relaunch open group focus: \(focused())")
        pick("News", .up, wait: 2)
        shot("news-gate")
        check(textContaining("Parental PIN").exists, "PIN asked for locked group")
        // wrong PIN x5 → lockout
        for i in 0..<5 { enterPin("2222"); log("wrong \(i + 1): \(textContaining("Wrong").exists) lockout: \(textContaining("Too many").exists)") }
        shot("lockout")
        check(textContaining("Too many wrong attempts").exists, "lockout message")
        enterPin("1111")
        shot("correct-during-lockout")
        check(textContaining("Parental PIN").exists, "correct PIN refused during lockout")
        Thread.sleep(forTimeInterval: 31)
        shot("lockout-over")
        enterPin("1111")
        shot("unlocked-news")
        check(!textContaining("Parental PIN").exists, "unlocked after lockout")
        // locked channel in Sports: now session unlocked, so it plays without prompt
        // Remove the PIN at the end (session unlocked → no prompt)
        openSettings()
        pick("Remove PIN", .down, wait: 2)
        if textContaining("Parental PIN").exists { enterPin("1111") }
        shot("pin-removed")
        check(textContaining("Set a PIN").exists, "PIN removed")
        crashCheck("pin end")
    }

    // MARK: 10. stress with the 21.7k-channel account

    func test10Big() {
        let t = Date()
        launch(["-resetData", "-demoXtream", "http://localhost:8900", "-demoUser", "big"], wait: 2)
        let tab = app.buttons["Live TV"]
        _ = tab.waitForExistence(timeout: 900)
        log(String(format: "big: reset+sync until tabs %.1fs", Date().timeIntervalSince(t)))
        Thread.sleep(forTimeInterval: 3)
        shot("big-open")
        log("open focus \(focused())")
        // spam keys while the guide loads
        press(.select, wait: 3)
        shot("big-group")
        let d = Date()
        for _ in 0..<120 { remote.press(.down); Thread.sleep(forTimeInterval: 0.08) }
        log(String(format: "120 downs sent in %.1fs", Date().timeIntervalSince(d)))
        Thread.sleep(forTimeInterval: 1)
        shot("big-after-120-down")
        log("focus after downs \(focused())")
        press(.right, wait: 1)
        for _ in 0..<40 { remote.press(.down); Thread.sleep(forTimeInterval: 0.08) }
        Thread.sleep(forTimeInterval: 1.5)
        shot("big-grid-downs")
        for _ in 0..<10 { remote.press(.right); Thread.sleep(forTimeInterval: 0.15) }
        Thread.sleep(forTimeInterval: 2)
        shot("big-grid-rights")
        press(.playPause, wait: 1.5)
        shot("big-now")
        // group switching
        press(.menu, wait: 1)
        press(.menu, wait: 1)
        let g = Date()
        for i in 0..<15 {
            press(.down, wait: 0.3)
            press(.select, wait: 0.6)
            if i % 5 == 4 { shot("big-group-switch-\(i)") }
            press(.left, wait: 0.4)
        }
        log(String(format: "15 group switches %.1fs", Date().timeIntervalSince(g)))
        log("focus after switching \(focused())")
        // All channels
        press(.menu, wait: 1)
        for _ in 0..<30 { remote.press(.up); Thread.sleep(forTimeInterval: 0.1) }
        Thread.sleep(forTimeInterval: 1)
        log("top group focus \(focused())")
        let allBtn = app.buttons.matching(NSPredicate(format: "label CONTAINS 'All'")).firstMatch
        log("all button: \(allBtn.exists) \(allBtn.label)")
        moveTo("All", with: .down, max: 5)
        let a = Date()
        press(.select, wait: 0.2)
        for _ in 0..<40 where !focused().contains("1") { Thread.sleep(forTimeInterval: 0.25) }
        log(String(format: "All channels open %.1fs focus %@", Date().timeIntervalSince(a), focused()))
        shot("big-all")
        for _ in 0..<150 { remote.press(.down); Thread.sleep(forTimeInterval: 0.05) }
        Thread.sleep(forTimeInterval: 2)
        shot("big-all-150-down")
        // fullscreen + zapping 50x
        press(.select, wait: 5)
        shot("big-fullscreen")
        let z = Date()
        for _ in 0..<50 { remote.press(.down); Thread.sleep(forTimeInterval: 0.2) }
        log(String(format: "50 zaps %.1fs", Date().timeIntervalSince(z)))
        Thread.sleep(forTimeInterval: 6)
        shot("big-after-zaps")
        crashCheck("big zaps")
        // channel list in All (21.7k rows)
        let l = Date()
        press(.select, wait: 0.3)
        for _ in 0..<20 where !focused().contains("Button") && !(app.scrollViews.count > 0) { Thread.sleep(forTimeInterval: 0.25) }
        log(String(format: "channel list open %.1fs", Date().timeIntervalSince(l)))
        Thread.sleep(forTimeInterval: 1.5)
        shot("big-list")
        for _ in 0..<80 { remote.press(.down); Thread.sleep(forTimeInterval: 0.06) }
        Thread.sleep(forTimeInterval: 2)
        shot("big-list-scrolled")
        press(.select, wait: 5)
        shot("big-list-tuned")
        press(.menu, wait: 1)
        press(.menu, wait: 2.5)
        shot("big-back-guide")
        log("guide focus after exit \(focused())")
        // search
        goToTab("Search")
        press(.down, wait: 1.5)
        let s = Date()
        type("news")
        Thread.sleep(forTimeInterval: 0.5)
        let ch = app.staticTexts["Channels"]
        _ = ch.waitForExistence(timeout: 30)
        log(String(format: "big search results %.1fs", Date().timeIntervalSince(s)))
        press(.down, 4, wait: 0.6)
        shot("big-search")
        // Movies tab with 103k movies
        goToTab("Movies")
        let m = Date()
        press(.down, wait: 0.5)
        _ = app.buttons.matching(NSPredicate(format: "label CONTAINS '★' OR label CONTAINS 'Movie'")).firstMatch.waitForExistence(timeout: 20)
        log(String(format: "movies tab %.1fs", Date().timeIntervalSince(m)))
        press(.right, wait: 1.5)
        for _ in 0..<60 { remote.press(.down); Thread.sleep(forTimeInterval: 0.1) }
        Thread.sleep(forTimeInterval: 2)
        shot("big-movies-scrolled")
        crashCheck("big end")
    }

    // MARK: 11. network loss (private server on :8912 driven by ctl.sh)

    func marker(_ name: String) {
        guard let dir else { return }
        try? Data().write(to: dir.appendingPathComponent("ctl-" + name))
        log("marker \(name)")
        Thread.sleep(forTimeInterval: 4)
    }

    func test11Network() {
        launch(["-resetData", "-demoXtream", "http://localhost:8912", "-playerHUD"], wait: 8)
        shot("expiry-toast")
        check(textContaining("expires").exists, "expiry reminder at launch")
        log("toast: \(textContaining("expires").label)")
        press(.select, wait: 2)
        press(.select, wait: 6)
        shot("playing")
        marker("stop")
        for i in 0..<6 { Thread.sleep(forTimeInterval: 4); shot("down-\(i)") }
        marker("start")
        for i in 0..<5 { Thread.sleep(forTimeInterval: 5); shot("up-\(i)") }
        check(!textContaining("Reconnecting").exists && !textContaining("failed").exists, "recovered after a 25 s outage")
        // long outage → give up?
        marker("stop")
        Thread.sleep(forTimeInterval: 45)
        shot("long-outage")
        log("status after long outage: \(app.staticTexts.allElementsBoundByIndex.map(\.label).filter { $0.contains("fail") || $0.contains("Reconn") })")
        marker("start")
        Thread.sleep(forTimeInterval: 20)
        shot("after-long-outage")
        press(.down, wait: 6)
        shot("zap-after-recovery")
        press(.menu, wait: 1)
        press(.menu, wait: 2)
        // sync failure: refresh with server down
        marker("stop")
        goToTab("Settings")
        press(.down, wait: 1.5)
        focusOn(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Refresh all'")).firstMatch)
        press(.select, wait: 6)
        shot("refresh-server-down")
        log("source row: \(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Xtream'")).firstMatch.label)")
        goToTab("Live TV")
        press(.down, wait: 2)
        shot("guide-after-failed-sync")
        check(app.buttons.matching(NSPredicate(format: "label CONTAINS 'News'")).firstMatch.exists, "lineup kept after failed refresh")
        marker("start")
        // delete a source while it plays in the preview
        press(.select, wait: 2)
        press(.right, wait: 1)
        press(.select, wait: 5)
        shot("preview-playing")
        goToTab("Settings")
        press(.down, wait: 1.5)
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Xtream'")).firstMatch
        focusOn(row)
        press(.select, wait: 1.5)
        pick("Delete", wait: 1.5)
        shot("delete-alert")
        log("alert buttons: \(app.alerts.buttons.allElementsBoundByIndex.map(\.label)) focus \(focused())")
        moveTo("Delete", with: .up, max: 3)
        marker("deleting")
        press(.select, wait: 4)
        shot("after-delete-while-playing")
        Thread.sleep(forTimeInterval: 10)
        marker("check-requests")
        shot("welcome-after-delete")
        crashCheck("network end")
    }

    func test00Probe() {
        launch(["-resetData"] + demo)
        press(.select, wait: 2)
        press(.right, wait: 1)
        hold()
        log(app.debugDescription)
        press(.menu, wait: 1)
        press(.menu, wait: 1)
        press(.menu, wait: 1)
        goToTab("Settings")
        press(.down, wait: 1.5)
        log(app.debugDescription)
    }

    func test12Options() {
        launch(demo + ["-playerHUD"])
        moveTo("News", with: .down, max: 8)
        press(.select, wait: 2)
        press(.select, wait: 5)
        press(.down, wait: 1)
        press(.down, wait: 5)
        shot("on-ch3")
        log("info texts: \(app.staticTexts.allElementsBoundByIndex.map(\.label).filter { $0.contains("Business") || $0.contains("World") })")
        remote.press(.select, forDuration: 2.0)
        Thread.sleep(forTimeInterval: 1.5)
        shot("hold-2s")
        log("after 2s hold: cells=\(menuItems()) list open=\(app.buttons.matching(NSPredicate(format: "label BEGINSWITH '10, IC'")).firstMatch.exists) focus=\(focused())")
        press(.menu, wait: 1.5)
        shot("after-menu")
        log("focus \(focused())")
        remote.press(.select, forDuration: 4.0)
        Thread.sleep(forTimeInterval: 1.5)
        shot("hold-4s")
        log("after 4s hold: cells=\(menuItems()) focus=\(focused())")
        if !menuItems().isEmpty {
            pick("Add to Favorites", wait: 2)
            remote.press(.select, forDuration: 2.0)
            Thread.sleep(forTimeInterval: 1.5)
            log("options after fav: \(menuItems())")
            press(.menu, wait: 1)
        } else {
            press(.menu, wait: 1.5)
        }
        // exit: where does guide focus land?
        for i in 0..<3 {
            press(.menu, wait: 1.5)
            log("menu \(i + 1): focus \(focused()) guide visible=\(app.staticTexts["Groups"].isHittable)")
            if app.staticTexts["Groups"].isHittable { break }
        }
        Thread.sleep(forTimeInterval: 1)
        shot("guide-after-exit")
        log("guide focus after exit from ch3: \(focused())")
        check(focusIs("Business Live"), "guide focus returns to the channel that was playing (Business Live)")
        // Earlier programmes from a programme cell
        moveTo("Daily News", with: .up, max: 4)
        if focusIs("News") && !focusIs("Daily") { press(.select, wait: 2) }
        press(.right, wait: 1.5)
        log("cell focus \(focused())")
        hold()
        log("programme menu: \(menuItems())")
        shot("prog-menu")
        pick("Earlier programmes", wait: 2)
        shot("earlier-from-cell")
        log("after earlier: \(focused())")
        for i in 0..<5 { press(.left, wait: 1.2); log("left \(i + 1): \(focused())") }
        shot("earlier-lefts")
        hold()
        log("past menu: \(menuItems())")
        shot("past-menu")
        pick("Back to now", wait: 2)
        shot("back-to-now")
        log("after back to now: \(focused())")
        // catch-up: Left into the previous programme and select
        press(.left, wait: 1.2)
        log("past focus \(focused())")
        shot("past-cell")
        press(.select, wait: 7)
        shot("catchup")
        log("catchup HUD: \(app.staticTexts.allElementsBoundByIndex.map(\.label).filter { $0.contains("status=") })")
        press(.menu, wait: 2)
        shot("catchup-menu1")
        press(.menu, wait: 2)
        shot("catchup-menu2")
        log("after catchup focus \(focused())")
        crashCheck("options end")
    }

    func openStation() {
        goToTab("Search")
        press(.down, wait: 1.5)
        type("station")
        Thread.sleep(forTimeInterval: 2)
        press(.down, 4, wait: 0.6)
        log("search focus \(focused())")
        moveTo("Station", with: .down, max: 4)
        press(.select, wait: 3)
        log("opened \(focused()) \(textContaining("Harbor Lights").exists)")
    }

    func test13BackAndUpNext() {
        launch(demo)
        if ProcessInfo.processInfo.environment["SKIP_BACK"] == nil {
        // Back from each non-Live tab
        for tabName in ["Movies", "Series", "Search", "Settings"] {
            goToTab(tabName)
            press(.down, wait: 1.5)
            if tabName == "Movies" || tabName == "Series" { press(.right, wait: 1.5) }
            log("\(tabName): content focus \(focused())")
            press(.menu, wait: 1.5)
            log("\(tabName): after Menu 1 focus \(focused())")
            if tabName == "Movies" { shot("movies-menu-1") }
            if focused().contains(tabName) && !focused().contains(",") {
                // on the tab bar: does Menu return to Live TV or exit?
                press(.menu, wait: 2)
                log("\(tabName): after Menu 2 state=\(app.state.rawValue) focus \(focused())")
                if app.state != .runningForeground { shot("\(tabName)-exited"); app.activate(); Thread.sleep(forTimeInterval: 3) }
            } else {
                press(.menu, wait: 1.5)
                log("\(tabName): after Menu 2 focus \(focused()) state=\(app.state.rawValue)")
                press(.menu, wait: 2)
                log("\(tabName): after Menu 3 state=\(app.state.rawValue) focus \(focused())")
                if app.state != .runningForeground { app.activate(); Thread.sleep(forTimeInterval: 3) }
            }
        }
        }
        // Series with 5 episodes: Up next → Cancel; then Menu; then countdown expiry
        openStation()
        shot("station-detail")
        let card = textContaining("Up next")
        func playToEnd(_ ep: String) {
            moveTo(ep, with: .down, max: 8)
            log("playing \(focused())")
            press(.select, wait: 7)
            for _ in 0..<29 { remote.press(.right); Thread.sleep(forTimeInterval: 0.4) }
            _ = card.waitForExistence(timeout: 45)
        }
        playToEnd("1. Pilot")
        shot("upnext-a")
        check(card.exists, "up next after Pilot")
        pick("Cancel", .right, wait: 3)
        shot("after-cancel")
        log("after cancel: \(focused()) detail? \(textContaining("Station").exists)")
        playToEnd("2. Arrival")
        shot("upnext-b")
        press(.menu, wait: 3)
        shot("after-menu")
        log("after menu on up next: \(focused())")
        playToEnd("3. Storm")
        shot("upnext-c")
        log("upnext c: \(card.label)")
        Thread.sleep(forTimeInterval: 13)
        shot("countdown-expired")
        log("after countdown: up next gone? \(!card.exists) focus \(focused())")
        press(.menu, wait: 1.5)
        press(.menu, wait: 3)
        shot("after-auto-next")
        log("detail after auto next: \(app.buttons.allElementsBoundByIndex.map(\.label).filter { $0.contains("Season") || $0.contains(". ") })")
        // Auto-next off
        press(.menu, wait: 1.5)
        press(.menu, wait: 1.5)
        openSettings()
        pick("Play next episode automatically", .down, wait: 1)
        log("auto next row: \(rowLabel("Play next episode automatically"))")
        openStation()
        playToEnd("1. Pilot")
        Thread.sleep(forTimeInterval: 3)
        shot("autonext-off-end")
        check(!card.exists, "no up next when the option is off")
        log("after end with auto-next off: \(focused())")
        // restore
        press(.menu, wait: 1.5)
        press(.menu, wait: 1.5)
        openSettings()
        pick("Play next episode automatically", .down, wait: 1)
        log("auto next row restored: \(rowLabel("Play next episode automatically"))")
        crashCheck("back/upnext end")
    }

    func pinRows() -> [String] {
        app.buttons.allElementsBoundByIndex.map(\.label).filter { $0.contains("PIN") }
    }

    func test14PinState() {
        launch(demo)
        openSettings()
        log("rows at launch: \(pinRows())")
        if pinRows().contains(where: { $0.hasPrefix("Remove PIN") }) {
            moveTo("Remove PIN", with: .down, max: 20, wait: 0.4)
            press(.select, wait: 2)
            if textContaining("Parental PIN").exists { enterPin("1111") }
            Thread.sleep(forTimeInterval: 1)
            shot("after-remove")
            log("rows right after Remove PIN: \(pinRows())")
            goToTab("Live TV")
            openSettings()
            log("rows after switching tabs: \(pinRows())")
            app.terminate()
            launch(demo)
            openSettings()
            log("rows after relaunch: \(pinRows())")
        }
        // set a PIN, then look at the rows without leaving the screen
        moveTo("Set a PIN", with: .down, max: 20, wait: 0.4)
        press(.select, wait: 2)
        enterPin("1111")
        enterPin("1111")
        Thread.sleep(forTimeInterval: 1)
        shot("after-set")
        log("rows right after setting: \(pinRows())")
        // remove again (cleanup)
        goToTab("Live TV")
        openSettings()
        log("rows after tab switch: \(pinRows())")
        moveTo("Remove PIN", with: .down, max: 20, wait: 0.4)
        press(.select, wait: 2)
        if textContaining("Parental PIN").exists { enterPin("1111") }
        app.terminate()
        launch(demo)
        openSettings()
        log("final rows: \(pinRows())")
    }

    func test15DeleteWhilePlaying() {
        launch(["-resetData", "-demoXtream", "http://localhost:8912"], wait: 8)
        moveTo("News", with: .down, max: 6)
        press(.select, wait: 2)
        press(.right, wait: 1)
        press(.select, wait: 6)
        shot("preview")
        goToTab("Settings")
        press(.down, wait: 1.5)
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Xtream'")).firstMatch
        focusOn(row)
        press(.select, wait: 1.5)
        pick("Delete", wait: 1.5)
        press(.right, wait: 1)
        log("alert focus \(focused())")
        marker("before-delete")
        press(.select, wait: 3)
        shot("after-delete")
        log("welcome? \(text("Add your playlist")) focus \(focused())")
        Thread.sleep(forTimeInterval: 8)
        marker("after-8s")
        Thread.sleep(forTimeInterval: 8)
        marker("after-16s")
        shot("later")
        // keys on the welcome screen right after deletion
        for _ in 0..<5 { remote.press(.menu); Thread.sleep(forTimeInterval: 0.3) }
        Thread.sleep(forTimeInterval: 1)
        log("state after menu spam on welcome: \(app.state.rawValue)")
    }

    func test16AllChannelsOk() {
        launch(["-resetData", "-demoXtream", "http://localhost:8900", "-demoUser", "big"], wait: 2)
        _ = app.buttons["Live TV"].waitForExistence(timeout: 900)
        Thread.sleep(forTimeInterval: 4)
        log("open focus \(focused())")
        for attempt in 0..<2 {
            moveTo("All channels", with: .up, max: 6)
            log("focus before OK \(focused())")
            let t = Date()
            press(.select, wait: 0.1)
            for _ in 0..<30 where app.staticTexts["Groups"].isHittable && !focusIs(", ") { Thread.sleep(forTimeInterval: 0.3) }
            log(String(format: "attempt %d: after OK %.1fs guide visible=%@ focus=%@", attempt, Date().timeIntervalSince(t), app.staticTexts["Groups"].isHittable ? "yes" : "no", focused()))
            Thread.sleep(forTimeInterval: 4)
            shot("all-ok-\(attempt)")
            log("attempt \(attempt): 4s later guide visible=\(app.staticTexts["Groups"].isHittable) focus=\(focused())")
            if !app.staticTexts["Groups"].isHittable { press(.menu, wait: 1.5); press(.menu, wait: 2) }
            press(.menu, wait: 1.5)  // back to the group list
            // switch to another group and back
            press(.down, wait: 0.6)
            press(.select, wait: 3)
            press(.left, wait: 1)
        }
    }

    /// With a PIN set, changing playlists asks for it; in the form, Down from the last row of
    /// fields goes to the main button rather than Cancel.
    func test17PlaylistPinAndFormFocus() {
        launch(["-resetData"] + demo)
        openSettings()
        moveTo("Set a PIN", with: .down, max: 20, wait: 0.4)
        press(.select, wait: 2)
        enterPin("1111")
        enterPin("1111")
        app.terminate()
        launch(demo) // a new session: locked again
        openSettings()
        moveTo("Add playlist", with: .down, max: 6, wait: 0.4)
        press(.select, wait: 2)
        check(textContaining("Parental PIN").exists, "Add playlist asks for the PIN")
        enterPin("1111")
        Thread.sleep(forTimeInterval: 1)
        check(textContaining("Add a playlist").exists, "the form opens after the PIN")
        moveTo("User-Agent", with: .down, max: 10, wait: 0.6)
        log("form focus \(focused())")
        press(.down, wait: 1)
        shot("form-down-from-user-agent")
        check(focusIs("Add playlist"), "Down from User-Agent goes to Add playlist")
        press(.menu, wait: 2)
        // cleanup: remove the PIN
        openSettings()
        moveTo("Remove PIN", with: .down, max: 20, wait: 0.4)
        press(.select, wait: 2)
        if textContaining("Parental PIN").exists { enterPin("1111") }
        crashCheck("playlist PIN")
    }

    func test18AboutLicences() {
        launch(demo)
        openSettings()
        check(moveTo("Licence", with: .down, max: 40, wait: 0.4), "Licence row in About")
        press(.select, wait: 2)
        shot("licence")
        check(textContaining("GNU GENERAL PUBLIC LICENSE").exists, "GPL text shown")
        press(.down, 5, wait: 0.4)
        press(.menu, wait: 1.5)
        check(focusIs("Licence"), "Menu returns to the Licence row (focus \(focused()))")
        press(.down, wait: 0.6)
        press(.select, wait: 2)
        shot("components")
        check(textContaining("Apache License").exists, "Apache text shown")
        press(.menu, wait: 1.5)
        check(focusIs("Open-source components"), "Menu returns to the components row (focus \(focused()))")
    }

    /// App Store screenshots from the fixture server's showcase account (start it with --showcase).
    /// Runs only when STORE_SHOTS names an output folder; the shots are named for the listing.
    func test19StoreScreenshots() throws {
        let out = ProcessInfo.processInfo.environment["STORE_SHOTS"].map { URL(fileURLWithPath: $0) }
        try XCTSkipUnless(out != nil, "STORE_SHOTS not set")
        func store(_ name: String) {
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: out!.appendingPathComponent("\(name).png"))
            log("STORE \(name) focus=[\(focused())]")
        }
        launch(["-resetData", "-demoXtream", "http://localhost:8900", "-demoUser", "showcase", "-demoName", "My TV"], wait: 20)
        // Guide: All channels, focus on a documentary programme so the preview plays.
        moveTo("All channels", with: .up)
        press(.select, wait: 2)
        press(.down, 12, wait: 0.5)
        press(.right, wait: 1)
        press(.select, wait: 8)
        store("01-guide")
        // Fullscreen with the info bar (before it times out), then the channel list.
        press(.left, wait: 1)
        press(.select, wait: 2.5)
        store("02-player")
        press(.select, wait: 2.5)
        store("03-channels")
        press(.menu, wait: 1.5)
        press(.menu, wait: 2)
        goToTab("Movies")
        press(.down, wait: 2)
        press(.left, wait: 1)
        moveTo("Recently added", with: .up, max: 4)
        press(.select, wait: 2)
        press(.right, wait: 3)
        store("04-movies")
        press(.select, wait: 4)
        store("05-movie")
        goToTab("Series")
        press(.down, wait: 3)
        press(.select, wait: 4)
        store("06-series")
    }
}
