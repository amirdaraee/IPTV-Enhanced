# CLAUDE.md

Guidance for coding agents working in this repository. User-facing docs are in [README.md](README.md), developer docs in [docs/development.md](docs/development.md); the product spec and plan are in [docs/spec/SPEC.md](docs/spec/SPEC.md) and [docs/plan/IMPLEMENTATION_PLAN.md](docs/plan/IMPLEMENTATION_PLAN.md).

## What this is

**IPTV Enhanced**: a free, open-source (GPL-3.0-or-later) IPTV player for Android TV / Google TV / Fire TV, Android phones and tablets, and Apple TV. It plays the user's own M3U/XMLTV playlists and Xtream logins and ships no content. The app id is `com.amirdaraee.iptv` on both platforms; changing it would make existing installs lose their data. Internal code names still say *Tuner* (the `app.tuner.*` Kotlin packages, `TunerCore`, `Tuner.xcodeproj`).

## Layout

- `shared/`: Kotlin Multiplatform core (parsers, Xtream client, SQLDelight database, sync, EPG import, user-data backup). The only API the apps use is the `TunerCore` facade (`Tuner` in Swift).
- `androidApp/`: Compose for TV + Media3.
- `tvosApp/`: SwiftUI; `project.yml` → `xcodegen generate` → `Tuner.xcodeproj`. A build phase compiles the Kotlin framework through Gradle.
- `tools/fixture-server/`: local M3U/XMLTV/Xtream/HLS test server (`python3 tools/fixture-server/server.py`, port 8900; username `big` is a 21.7k-channel account; `showcase` is the made-up catalog for store screenshots, see docs/development.md).
- `tools/icons/`: `render.swift` draws the logo; `generate.sh` regenerates every icon, banner and `docs/brand/` image.

## Commands

Gradle needs JDK 17 (`JAVA_HOME`).

```sh
./gradlew :shared:jvmTest                     # core tests (PerformanceTest is timing-based: run it alone if it fails under load)
./gradlew :androidApp:assembleRelease         # release APK, signed with the debug key
cd tvosApp && xcodegen generate && xcodebuild -project Tuner.xcodeproj -scheme Tuner \
  -destination 'platform=tvOS Simulator,id=<udid>' build
```

Real Apple TV builds need `DEVELOPMENT_TEAM=<team> -allowProvisioningUpdates` on the command line (see README), so no signing team is stored in the project.

## Conventions

- **Change notifications are scoped.** Every mutation in `TunerCore` calls `changed(Changes.X)`; screens subscribe with `watchChanges(mask)`. A new mutation that doesn't call `changed(...)` won't refresh the UI *and* won't reach the user-data backup.
- **User data vs catalog.** Playlists, `item_pref`, `group_pref`, `progress`, `recent` and `setting` are the user's own data and are covered by `UserData` (backup/restore, `shared/.../UserData.kt`). The catalog and guide re-sync from the provider. If you add user-owned state, add it to the snapshot as well; keep transient security state (the PIN lockout) out.
- **tvOS storage.** On real hardware only Caches and tmp are writable, so the database lives in `Library/Caches/databases`; the simulator is more permissive, so storage changes must be checked on a device. The backup copy is in the Keychain (`KeychainBackup.swift`).
- **Touch.** The Android app also runs on phones and tablets (`LocalTouchUi`, `ui/TouchUi.kt`): the interface is scaled to fit the 960×540 dp TV design, and the TV surfaces get one-tap clicks from `touchClick`. A remote-only interaction (a key handler, a hint that says "press OK") needs a touch equivalent there.
- **TV input.** Everything must work with a D-pad remote. Act on an OK *release* only when the press started on the same element (see `okDown` in the guide, player and `TvTextField`); test Back on every screen you touch (it should close the innermost thing first).
- **Suspend functions exposed to Swift** need `@Throws(Exception::class)`, or a Kotlin exception crashes the app.
- **Store rules.** UI and docs say "Xtream login", not "Xtream Codes"; never name real channels, broadcasters or other apps (that includes the fixture server's made-up names); demo content must be freely shareable (`docs/demo/`), and screenshots use only the showcase account's invented names and generated art. A new runtime dependency needs an entry in `licenses/third-party.txt`, which Settings → About shows.
- Match the surrounding style: short KDoc explaining *why*, no speculative abstractions.

## Credentials

Never commit provider credentials. Tests against real accounts read them from environment variables (`LIVE_XTREAM`, `TEST_RUNNER_LIVE_XTREAM`) and are skipped without them. Many IPTV accounts allow a single connection, so tests that play streams can interrupt other viewers of the same account.
