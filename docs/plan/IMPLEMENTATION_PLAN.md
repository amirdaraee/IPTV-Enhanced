# Tuner: Implementation Plan

Companion to [SPEC.md](../spec/SPEC.md). Tasks are ordered by dependency. `[x]` means done and committed.

## Repository layout
```
shared/                 KMP core (commonMain, jvmAndroidMain, androidMain, jvmMain, appleMain)
androidApp/             Android TV app (Compose for TV, Media3)
tvosApp/                tvOS app (SwiftUI); project.yml → xcodegen → Tuner.xcodeproj
tools/fixture-server/   Local test server: M3U, XMLTV, fake Xtream API, HLS
docs/                   spec, feature list, plan, brand artwork
```
Toolchain: Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.2, JDK 17, compileSdk 37, Xcode 27, tvOS 17+ deployment.

## M1: Shared core
- [x] 1.1 Gradle KMP scaffold (android library, jvm, tvosArm64, tvosSimulatorArm64 → XCFramework)
- [x] 1.2 `TextPipeline`: gzip sniffing, platform zlib inflater (java.util.zip / platform.zlib), streaming UTF-8
- [x] 1.3 `M3uParser` push parser with header and EXTINF attributes, EXTVLCOPT/KODIPROP/pipe headers, VOD classification
- [x] 1.4 `XmlTokenizer` + `XmltvParser` push parser; `parseXmltvTime`
- [x] 1.5 `XtreamClient` (auth, categories, streams, series, series/vod info, short EPG, URL builders) with tolerant JSON
- [x] 1.6 `CatchupUrlBuilder` (default/append/shift/flussonic/xtream)
- [x] 1.7 SQLDelight schema and queries (tables from SPEC §4) plus platform drivers
- [x] 1.8 `HttpDownloader`: streams a URL through `TextPipeline` with headers and progress
- [x] 1.9 `SourceSyncer`: M3U and Xtream sync with the merge rule and batched transactions
- [x] 1.10 `EpgImporter`: retention window, offset, id and name matching, batched insert
- [x] 1.11 Repositories: channels, guide queries (programmes for N channels in a time window), VOD, progress, recents, search, settings, PIN
- [x] 1.12 `TunerCore` facade: Swift-friendly suspend API plus callback observers; main-thread delivery
- [x] 1.13 Tests: Xtream MockEngine, sync merge, EPG import, catch-up templates, gzip

## M2: Android TV app
- [x] 2.1 App shell: theme, `TunerApp` (core singleton), navigation graph, exit confirmation
- [x] 2.2 Onboarding / add source (M3U & Xtream forms with validation and sync progress)
- [x] 2.3 Home: side nav rail (Live Guide, Movies, Series, Search, Settings)
- [x] 2.4 Guide: group sidebar, channel column, virtualised programme grid with time header and now line, focus info header, preview player
- [x] 2.5 Player screen: Media3 with headers, info bar, channel overlay, zapping, last channel, reconnect, tracks/aspect menu
- [x] 2.6 Context menu (long-press): favorite, hide, lock, catch-up; group management (hide/reorder)
- [x] 2.7 Movies and Series: category rows, details, seasons/episodes, resume, continue watching
- [x] 2.8 Search screen
- [x] 2.9 Settings: sources list (edit/refresh/delete), guide window, time format, overlay timeout, PIN, about
- [x] 2.10 PIN dialog and lock enforcement

## M3: tvOS app
- [x] 3.1 App shell: `AppModel` wrapping `TunerCore`, TabView (Live, Movies, Series, Search, Settings)
- [x] 3.2 Add source flow
- [x] 3.3 Guide: groups list, `LazyVStack` of rows with a horizontally scrolling programme strip on a shared time axis, focus header
- [x] 3.4 Player: `AVPlayerViewController` wrapper with header injection (AVURLAsset options), channel up/down via swipe, channel overlay, info bar, reconnect
- [x] 3.5 Movies/Series grids, details, episodes, resume
- [x] 3.6 Search, settings, PIN, context menus (`.contextMenu`)
- [ ] 3.7 App icon / top shelf assets

## M4: Hardening
- [x] 4.1 `tools/fixture-server` (Python stdlib): M3U with 5k channels, gz XMLTV, fake Xtream API, HLS samples via ffmpeg
- [x] 4.2 Android emulator E2E: add source, guide, play, zap, VOD resume (adb keyevents and screenshots)
- [x] 4.3 tvOS simulator E2E with the same flows
- [ ] 4.4 Performance: 50k-channel and 200 MB-EPG import timing on JVM and the tvOS simulator
- [ ] 4.5 README (build, run, architecture, legal notice), CONTRIBUTING, screenshots

## Status notes (2026-10-24)
- **Android TV**: verified end to end on the Google TV emulator (API 36) against the fixture server:
  - onboarding;
  - guide with preview;
  - fullscreen, zapping, channel list and catch-up;
  - movies and series with resume;
  - search and settings.
- **tvOS**: these flows were verified with XCUITest on the tvOS 27 simulator: guide, focus navigation, window scrolling, catch-up area, channel list, movies, series, search and settings.
  - The simulator here runs headless (no Simulator.app with Xcode 27), so its media clock never runs.
  - AVPlayer loads, buffers and renders the first frame of HLS and MP4, but continuous playback needs a real Apple TV.
- **Security review** fixes:
  - parental locks are enforced in the core, including group locks and resume entries, and fail closed;
  - PIN brute-force lockout;
  - capped parser buffers;
  - ATS configured so plain-HTTP provider APIs work on real domains.

## Next (v1.x)
- [ ] 3.7 App icon / top shelf brand assets
- [ ] Additional playback engine on tvOS for raw MPEG-TS and MKV
- [ ] Phone-based setup (QR code + LAN web form)
- [ ] Backup/restore and user-owned sync (iCloud KVS on tvOS, file/WebDAV on Android)
- [ ] Reminders, manual EPG mapping, channel renaming and reordering
- [ ] 4.4 Performance pass with 50k-channel and 200 MB EPG fixtures on device

## Risks & mitigations
| Risk | Mitigation |
|------|-----------|
| AVPlayer can't play raw MPEG-TS live streams, which are common for Xtream | Prefer `.m3u8` output for Xtream live on tvOS (`allowed_output_formats`); an additional engine is planned for v1.x |
| Very large XMLTV on the tvOS memory budget | Streaming push parser; no DOM; batched inserts |
| Kotlin↔Swift interop friction | Narrow facade with plain types; callbacks instead of Flow; `objcExportSuspendFunctionLaunchThreadRestriction=none` |
| Focus handling in big grids | Virtualised lazy lists; explicit focus restoration keyed by channel id |
