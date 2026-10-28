# Development

How IPTV Enhanced is built, tested and released. For what the app does, see the [README](../README.md).

## Architecture

```
shared/        Kotlin Multiplatform core: parsers, Xtream client, SQLDelight DB, sync, EPG import, facade
androidApp/    Android TV app (Compose for TV + Media3)
tvosApp/       tvOS app (SwiftUI); links the core as TunerCore.framework
tools/         fixture-server (local test server), demo playlist generator, icon renderer
docs/          spec, feature list, plan, privacy policy, demo playlist, store assets and brand artwork
```

Both apps use one facade, `TunerCore` (called `Tuner` in Swift). The core keeps memory bounded on large providers in two ways:
- **Push parsers.** A streaming M3U parser and an XML tokenizer process network chunks as they arrive, through gzip detection, zlib and UTF-8 decoding.
- **Atomic sync.** New data is staged under a shadow source id and swapped in with a single transaction, so a failed refresh never leaves a half-updated lineup.

See [spec/SPEC.md](spec/SPEC.md) and [plan/IMPLEMENTATION_PLAN.md](plan/IMPLEMENTATION_PLAN.md).

## Building

Requirements:
- JDK 17
- Android SDK with platform 37
- Xcode 27 or newer (for tvOS)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)

```sh
# Core tests (JVM + tvOS simulator)
./gradlew :shared:jvmTest :shared:tvosSimulatorArm64Test

# Android TV
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

# tvOS (the Xcode build phase compiles the Kotlin framework via Gradle)
cd tvosApp && xcodegen generate
xcodebuild -project Tuner.xcodeproj -scheme Tuner -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation) (at 1080p)' build
```

## CI and releases

GitHub Actions (`.github/workflows/build.yml`) runs the core tests and builds both apps on every push to `main` and every pull request. Each run keeps the APK, the Android app bundle (AAB) for Google Play and an unsigned Apple TV app as artifacts. Pushing a `v*` tag also publishes a GitHub release with the APK.

Release builds are signed with an upload key when one is configured, and with the debug key otherwise:
- **Locally:** set `iptvUploadStoreFile` (the keystore path) and `iptvUploadPassword` in `~/.gradle/gradle.properties`. The key alias is `upload`.
- **In CI:** set the `UPLOAD_KEYSTORE` secret (the keystore, base64-encoded) and the `UPLOAD_PASSWORD` secret.

## Fixture server

`tools/fixture-server` is a stdlib-only Python server. It serves:
- a 40-channel M3U;
- a gzipped XMLTV guide covering −2 to +3 days;
- a fake Xtream API (any username and password; the password `bad` fails);
- real-time live HLS (ffmpeg loops test patterns);
- raw MPEG-TS live streams;
- MP4 VOD;
- a large-provider Xtream account (username `big`, any password): 21.7k channels, 103k movies, 25k series and 200k programmes, sharing the four test streams;
- a showcase Xtream account (username `showcase`) with made-up channels, films and series and generated artwork, for screenshots (see below).

```sh
tools/fixture-server/generate_media.sh      # once; needs ffmpeg
python3 tools/fixture-server/server.py      # http://localhost:8900
```

To connect:
- **Android emulator:** use `http://10.0.2.2:8900`.
- **tvOS simulator:** use `http://localhost:8900`.

In a headless tvOS simulator (no audio device), start the server with `--no-audio`. AVPlayer can't start streams that carry audio there (CoreMedia error -66681); video-only streams play normally.

Debug builds of the tvOS app accept these launch arguments for testing:
- `-resetData` removes all sources first (first-run state).
- `-demoXtream <server>` pre-adds the fixture Xtream account; `-demoName`/`-demoUser`/`-demoPass` override its name and credentials and `-demoM3U <url>` adds a playlist.
- `-debugPlay <url>` opens the system player on a URL; add `-debugLive` for live behaviour.
- `-playerHUD` shows on-screen player diagnostics.

The tvOS UI tests (`TunerUITests`) drive the Siri Remote against the server:

```sh
cd tvosApp && TEST_RUNNER_SHOTS_DIR=/tmp/shots xcodebuild test -project Tuner.xcodeproj -scheme Tuner \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation) (at 1080p)'
```

## Store screenshots

Screenshots use the showcase account, so they show no real channels or anyone else's artwork. Everything in it is invented and drawn by `make_showcase_art.py`; keep it that way.

```sh
python3 tools/fixture-server/make_showcase_art.py   # once; needs Pillow, numpy and ffmpeg (macOS fonts)
python3 tools/fixture-server/server.py --showcase
```

- **Apple TV:** `test19StoreScreenshots` walks through the guide, player, Movies and Series and saves 3840×2160 shots. Run it on an Apple TV 4K simulator:

  ```sh
  cd tvosApp && TEST_RUNNER_STORE_SHOTS=/tmp/store xcodebuild test -project Tuner.xcodeproj -scheme Tuner \
    -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' -only-testing:TunerUITests/E2EUITests/test19StoreScreenshots
  ```

- **Android TV:** on a 1080p Google TV emulator, add an Xtream login with server `http://10.0.2.2:8900` and username `showcase`, then take each shot with `adb exec-out screencap -p`. Cold-boot the emulator (`-no-snapshot`) first: a restored snapshot keeps an old clock, and the guide then treats live programmes as past ones.
- **Android phone:** the same, on a phone emulator with a 1080×1920 screen held in landscape. Google Play accepts screenshots up to 2:1, and a 16:9 phone stays inside that. Swipe from the middle of the screen: a swipe near an edge shows the system bars, and the next tap only hides them again.
- **Android tablet:** the same, on a 2560×1600 tablet emulator in landscape.

The published shots are in [`store/`](store/), with the Google Play feature graphic (`tools/icons/generate.sh` renders it).

## Installing on a real Apple TV

Pair the Apple TV with Xcode once (Settings → Remotes and Devices → Remote App and Devices), then build with your signing team. Pass the team on the command line rather than committing it:

```sh
cd tvosApp && xcodegen generate
xcodebuild -project Tuner.xcodeproj -scheme Tuner -configuration Release -destination 'id=<device-udid>' \
  -derivedDataPath /tmp/tuner-dd DEVELOPMENT_TEAM=<team-id> -allowProvisioningUpdates ONLY_ACTIVE_ARCH=YES ARCHS=arm64 build
xcrun devicectl device install app --device <device-udid> /tmp/tuner-dd/Build/Products/Release-appletvos/Tuner.app
```

`xcrun devicectl list devices` shows the UDID. With a free Apple ID the signing profile lasts 7 days, after which the app stops launching until it is reinstalled; data is kept. On a real device, UI-test screenshots end up in the `.xcresult` bundle (`xcrun xcresulttool export attachments`), not in `SHOTS_DIR`.

`testLiveAccountFavorites` sets up a real Xtream account on a device and adds the channels matching a search (`TEST_RUNNER_LIVE_SEARCH`, default `news`) to Favorites; it is skipped unless `TEST_RUNNER_LIVE_XTREAM="server|user|pass"` is set.

## Testing against real providers

`LiveSourceTest` imports real sources over the network and reports timings, counts and sample playback URLs (written to `shared/build/live-urls.txt`). It is skipped unless configured, so credentials never live in the repo:

```sh
LIVE_M3U="https://provider.example/playlist.m3u" \
LIVE_XTREAM="http://provider.example|user|pass" \
./gradlew :shared:jvmTest --tests '*LiveSourceTest*'
```

## Performance

The apps are built to stay smooth with very large providers:
- **Targeted change notifications.** `watchChanges` takes a mask of `Changes` kinds, so each screen reloads only for changes it shows. Zapping (history) and VOD progress saves don't re-read a 20k-channel lineup.
- **Indexed search.** Guide search scans only programmes in the requested time window, using a start-time index, and looks up one channel per guide id.
- **Cheaper sync writes.**
  - Rows are written in batches of 2,000 (programmes: 5,000).
  - SQLite runs in WAL mode with `synchronous=NORMAL`.
  - On tvOS, extra reader connections keep the UI responsive while a sync writes.
- **Guide rendering.**
  - On Android, only the focused row recomposes when you move left or right, and core models are declared stable to Compose.
  - On tvOS, rows are equatable and the list is keyed by index, so a remote press re-renders just the rows whose focus changed.
- **Artwork.**
  - On Android, Coil uses a 256 MB disk cache.
  - On tvOS, `ImagePipeline` adds memory and disk caches, request de-duplication and decoding downsampled to the drawn size.
- **Zapping.**
  - Channel changes closer together than 350 ms are coalesced, so only the channel you stop on is opened. Single-connection accounts aren't refused mid-zap.
  - Android streams and artwork share one OkHttp connection pool.

`BenchmarkTest` times sync and every screen's queries against a mock panel of the same size as the `big` fixture account:

```sh
BENCH_DB=/tmp/bench.db ./gradlew :shared:jvmTest --tests '*BenchmarkTest*'            # builds the DB on first run
BENCH_DB=/tmp/bench.db BENCH_SYNC=1 ./gradlew :shared:jvmTest --tests '*BenchmarkTest*' # also times a full re-sync
```

Results are printed to the test report (`shared/build/test-results/jvmTest`). Set `TEST_JFR=/path/rec.jfr` to record a Java Flight Recorder profile of the test JVM.

## Demo playlist

`python3 tools/demo/make_demo.py` regenerates [`demo/playlist.m3u`](demo/playlist.m3u) and its guide. The guide runs for a year from its start date, so regenerate it before then.

## Icons

`tools/icons/generate.sh` renders the Android TV banner and launcher icon, the tvOS layered app icon and Top Shelf images, and the store artwork, from `tools/icons/render.swift`.
