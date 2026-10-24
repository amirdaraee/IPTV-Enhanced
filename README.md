# Tuner

**A free, open-source IPTV player for Android TV and Apple TV.**

Tuner is a TV guide and player for the playlists you already have: M3U/M3U8 playlists with XMLTV guides, and Xtream Codes accounts. It ships no content, has no accounts, and collects no telemetry. Everything stays on the device.

> Tuner is a player only. You are responsible for the content you load into it.

## Features (v0.1)

**Sources**
- Unlimited M3U/M3U8 playlists and Xtream Codes accounts.
- gzip-aware streaming import that handles playlists of any size.
- Custom User-Agent per source; `#EXTVLCOPT`, `#KODIPROP` and `url|Header=` headers are respected.
- Automatic refresh on start once a source is older than its interval.

**TV guide**
- XMLTV is streamed straight into SQLite, so 100 MB+ guides never sit in memory.
- Guide sources: the playlist's `url-tvg`, Xtream's `xmltv.php`, and extra URLs you add.
- Channels match guide data by `tvg-id`, with a normalised channel-name fallback.
- Per-source time shift, and configurable past and future retention.

**Live TV**
- A grid guide is the home screen, with a live preview.
- Groups, Favorites, Recents and All channels; hide or reorder groups, hide channels.
- Fullscreen player with:
  - an info bar showing now/next;
  - a channel-list overlay;
  - up/down zapping;
  - number-key entry (Android) and last-channel recall;
  - auto-reconnect.

**Catch-up**
- Works with Xtream `tv_archive` and the M3U `catchup` modes: default, append, shift and flussonic.
- Xtream timeshift URLs are built in the server's own time zone.

**Movies and Series**
- Category grids, details, seasons and episodes.
- Resume and Continue watching, plus favorites.

**Everything else**
- Unified search across channels, movies, series and programmes on air in the next 24 hours.
- Parental PIN: lock channels or groups (with a brute-force lockout), and protect settings.

## Platforms

| | Android TV / Google TV | Apple TV |
|-|------------------------|----------|
| Minimum OS | Android 7.0 (API 24) | tvOS 17 |
| UI | Jetpack Compose for TV | SwiftUI (native focus engine) |
| Player | Media3 ExoPlayer: HLS, DASH, MPEG-TS, MP4, MKV | AVPlayer: HLS, MP4 (Xtream live uses HLS) |

## Architecture

```
shared/        Kotlin Multiplatform core: parsers, Xtream client, SQLDelight DB, sync, EPG import, facade
androidApp/    Android TV app (Compose for TV + Media3)
tvosApp/       tvOS app (SwiftUI); links the core as TunerCore.framework
tools/         fixture-server: local M3U/XMLTV/Xtream/HLS test server
docs/          spec, feature list, implementation plan and brand artwork
```

Both apps use one facade, `TunerCore` (called `Tuner` in Swift). The core keeps memory bounded on large providers in two ways:
- **Push parsers.** A streaming M3U parser and an XML tokenizer process network chunks as they arrive, through gzip detection, zlib and UTF-8 decoding.
- **Atomic sync.** New data is staged under a shadow source id and swapped in with a single transaction, so a failed refresh never leaves a half-updated lineup.

See [docs/spec/SPEC.md](docs/spec/SPEC.md) and [docs/plan/IMPLEMENTATION_PLAN.md](docs/plan/IMPLEMENTATION_PLAN.md).

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

## Testing with the fixture server

`tools/fixture-server` is a stdlib-only Python server. It serves:
- a 40-channel M3U;
- a gzipped XMLTV guide covering −2 to +3 days;
- a fake Xtream API (any username and password; the password `bad` fails);
- real-time live HLS (ffmpeg loops test patterns);
- raw MPEG-TS live streams;
- MP4 VOD.

```sh
tools/fixture-server/generate_media.sh      # once; needs ffmpeg
python3 tools/fixture-server/server.py      # http://localhost:8900
```

To connect:
- **Android emulator:** use `http://10.0.2.2:8900`.
- **tvOS simulator:** use `http://localhost:8900`.

The tvOS UI tests (`TunerUITests`) drive the Siri Remote against the server:

```sh
cd tvosApp && TEST_RUNNER_SHOTS_DIR=/tmp/shots xcodebuild test -project Tuner.xcodeproj -scheme Tuner \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation) (at 1080p)'
```

## Icons

`tools/icons/generate.sh` renders the Android TV banner/launcher icon and the tvOS layered
app icon and Top Shelf images from `tools/icons/render.swift`.

## Remote controls

| | OK | Hold OK / Menu | Back | Up/Down | Left/Right |
|-|----|----------------|------|---------|-----------|
| Guide | Preview, then fullscreen; past programme → catch-up | Context menu | Groups → side menu | Channels | Time |
| Live player | Channel list | Options (tracks, picture, favorite) | Close overlay → guide | Zap | Info bar |

On Apple TV, the Siri Remote clickpad and swipes map to the same actions. Menu acts as Back.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

All code in this repository was written for this project. The only third-party files are Gradle's standard wrapper scripts (`gradlew`, `gradlew.bat`, `gradle/wrapper`), which are Apache-2.0; libraries are fetched as build dependencies and are not vendored.
