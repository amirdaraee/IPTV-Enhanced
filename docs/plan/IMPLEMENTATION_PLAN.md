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
- [ ] 2.1 App shell: theme, `TunerApp` (core singleton), navigation graph, exit confirmation
- [ ] 2.2 Onboarding / add source (M3U & Xtream forms with validation and sync progress)
- [ ] 2.3 Home: side nav rail (Live Guide, Movies, Series, Search, Settings)
- [ ] 2.4 Guide: group sidebar, channel column, virtualised programme grid with time header and now line, focus info header, preview player
- [ ] 2.5 Player screen: Media3 with headers, info bar, channel overlay, zapping, last channel, reconnect, tracks/aspect menu
- [ ] 2.6 Context menu (long-press): favorite, hide, lock, catch-up; group management (hide/reorder)
- [ ] 2.7 Movies and Series: category rows, details, seasons/episodes, resume, continue watching
- [ ] 2.8 Search screen
- [ ] 2.9 Settings: sources list (edit/refresh/delete), guide window, time format, overlay timeout, PIN, about
- [ ] 2.10 PIN dialog and lock enforcement

## M3: tvOS app
- [ ] 3.1 App shell: `AppModel` wrapping `TunerCore`, TabView (Live, Movies, Series, Search, Settings)
- [ ] 3.2 Add source flow
- [ ] 3.3 Guide: groups list, `LazyVStack` of rows with a horizontally scrolling programme strip on a shared time axis, focus header
- [ ] 3.4 Player: `AVPlayerViewController` wrapper with header injection (AVURLAsset options), channel up/down via swipe, channel overlay, info bar, reconnect
- [ ] 3.5 Movies/Series grids, details, episodes, resume
- [ ] 3.6 Search, settings, PIN, context menus (`.contextMenu`)
- [ ] 3.7 App icon / top shelf assets

## M4: Hardening
- [ ] 4.1 `tools/fixture-server` (Python stdlib): M3U with 5k channels, gz XMLTV, fake Xtream API, HLS samples via ffmpeg
- [ ] 4.2 Android emulator E2E: add source, guide, play, zap, VOD resume (adb keyevents and screenshots)
- [ ] 4.3 tvOS simulator E2E with the same flows
- [ ] 4.4 Performance: 50k-channel and 200 MB-EPG import timing on JVM and the tvOS simulator
- [ ] 4.5 README (build, run, architecture, legal notice), CONTRIBUTING, screenshots

## Risks & mitigations
| Risk | Mitigation |
|------|-----------|
| AVPlayer can't play raw MPEG-TS live streams, which are common for Xtream | Prefer `.m3u8` output for Xtream live on tvOS (`allowed_output_formats`); an additional engine is planned for v1.x |
| Very large XMLTV on the tvOS memory budget | Streaming push parser; no DOM; batched inserts |
| Kotlin↔Swift interop friction | Narrow facade with plain types; callbacks instead of Flow; `objcExportSuspendFunctionLaunchThreadRestriction=none` |
| Focus handling in big grids | Virtualised lazy lists; explicit focus restoration keyed by channel id |
