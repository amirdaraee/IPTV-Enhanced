# Tuner: Product & Technical Specification (v1.0)

Status: accepted · Date: 2026-10-24 · License: GPL-3.0-or-later

## 1. Purpose
Tuner is a free, open-source IPTV player for **Android TV / Google TV** and **Apple TV (tvOS)**. It plays content from playlists the user already has: M3U/M3U8 with XMLTV, and Xtream Codes accounts. It provides no content and no accounts, and sends no telemetry.

### Goals
1. Live TV first: the guide is home, zapping is instant, and the remote grammar never changes.
2. The same behaviour on both platforms, from one shared Kotlin core.
3. It handles real providers at scale: 50k channels, 100k VOD items and 200 MB XMLTV, with no UI stalls.
4. Every core feature is free: unlimited playlists, parental PIN, catch-up.

### Non-goals (v1)
Stalker/MAG portals, DVR/recording, casting, user profiles, multiview, ads or monetisation, and bundled content of any kind.

## 2. Platforms
| | Android TV | tvOS |
|-|-----------|------|
| Min OS | Android 7.0 (API 24) | tvOS 17 |
| UI | Jetpack Compose + `androidx.tv:tv-material` | SwiftUI |
| Player | Media3 ExoPlayer (HLS, DASH, TS, progressive) | AVPlayer (HLS, MP4); additional engine P1 |
| Core | `:shared` KMP module (Android library) | `TunerCore.framework` (static, built by Gradle) |

## 3. Architecture
```
┌──────────────── Android TV app ───────────────┐   ┌──────────────── tvOS app ─────────────────┐
│ Compose screens · ViewModels · Media3 player  │   │ SwiftUI views · ObservableObjects · AVKit │
└──────────────────────┬─────────────────────────┘   └──────────────────────┬────────────────────┘
                       │ Kotlin API                                         │ ObjC/Swift export
┌──────────────────────┴────────────────────────────────────────────────────┴────────────────────┐
│ shared (Kotlin Multiplatform)                                                                  │
│  TunerCore facade ─ repositories ─ SourceSyncer ─ EpgImporter ─ CatchupUrlBuilder ─ Search     │
│  parsers: M3uParser (push) · XmlTokenizer/XmltvParser (push) · XtreamClient (Ktor + JSON)      │
│  io: TextPipeline (gzip sniff → zlib inflate → UTF-8 decode)                                   │
│  storage: SQLDelight (SQLite) · Settings (key/value table)                                     │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

* **Push parsers.** Network bytes go through `TextPipeline` to the parser, and parsed items go into batched DB transactions of 1,000 rows. Memory stays bounded however big the input is, and the parser never suspends per character.
* **The facade is the only API the apps use.** `TunerCore` exposes plain suspend functions and observation callbacks (`watchX(callback): Cancellable`), because Kotlin `Flow` doesn't map cleanly to Swift. Each app owns its own UI state.
* **Threading.** All DB and network work runs on `Dispatchers.Default`/IO inside the core. Callbacks are delivered on the main thread.
* **The platform layer is small.** It holds the DB driver, the HTTP engine (OkHttp / Darwin), the zlib inflater and the player.

## 4. Data model (SQLite via SQLDelight)
| Table | Key columns | Notes |
|-------|-------------|-------|
| `source` | id, name, type(M3U\|XTREAM), url, username, password, epg_urls, user_agent, epg_offset_minutes, refresh_hours, last_refreshed, last_error, enabled, sort | Credentials are stored locally only |
| `category` | id, source_id, kind(LIVE\|MOVIE\|SERIES), remote_id, name, hidden, locked, sort | Groups for M3U; categories for Xtream |
| `channel` | id, source_id, category_id, name, number, logo, epg_id, url, remote_id, catchup_mode, catchup_days, catchup_source, user_agent, referrer, favorite, hidden, locked, sort | Unique (source_id, url) for M3U and (source_id, remote_id) for Xtream; user flags survive refreshes |
| `movie` | id, source_id, category_id, name, poster, url, remote_id, extension, rating, year, added_at, favorite | |
| `series` | id, source_id, category_id, name, cover, remote_id, plot, rating, year, favorite | Episodes are fetched on demand |
| `programme` | channel_key, start, stop, title, description, category, icon, episode | `channel_key` = normalised EPG id, indexed (channel_key, start) |
| `epg_channel` | key, source_id, display_name_norm | Supports the name-based fallback match |
| `progress` | key, kind, title, image, url, position_ms, duration_ms, updated_at, parent_id | Continue watching |
| `recent` | channel_id, watched_at | Last 30 |
| `setting` | key, value | Key/value settings and PIN hash |

**Refresh merge rule.** On a refresh, rows are upserted by natural key and user flags (favorite, hidden, locked, custom sort) are preserved. Rows missing from the new payload are deleted at the end of a successful sync. A failed sync rolls back and keeps the old data.

## 5. Functional requirements
Feature IDs refer to [03-feature-list.md](../03-feature-list.md). P0 requirements in detail:

### 5.1 Sources (SRC-1…6)
* **Add M3U.** Fields: name, URL, optional EPG URL(s), optional User-Agent. Validation downloads the playlist and requires at least one entry.
* **Add Xtream.** Fields: name, server, username, password. Validation calls `player_api.php` and shows the account status, expiry and max connections. The server URL is normalised: scheme added, `/player_api.php` and `/get.php` stripped.
* **Sync pipeline, per source.**
  * M3U: playlist, then categories and channels, movies and series-like VOD.
  * Xtream: categories(live/vod/series), then live streams, VOD streams and series. These run in parallel, three requests at most.
  * Either type then imports the EPG. Progress is reported as phase plus count.
* A refresh is triggered on launch when `now - last_refreshed > refresh_hours` (default 24), or manually. The EPG is refreshed on the same schedule.

### 5.2 EPG (EPG-1…6)
* Sources: M3U header URLs, the user's extra URLs, and Xtream `xmltv.php`.
* Before import, programmes outside `[now - pastDays, now + futureDays]` are dropped (defaults: 2 past, 7 future).
* Matching, for each channel:
  1. `epg_id` equals the XMLTV channel id. The comparison is case-insensitive.
  2. Otherwise, the normalised channel name equals a normalised display name. Normalisation lowercases, strips accents and punctuation, and removes quality tags (HD, FHD, UHD, 4K, SD, HEVC) and country prefixes such as `UK:` or `|US|`.
* The resolved key is stored on the channel (`epg_key`).
* The offset is applied at import: `start += offset`.

### 5.3 Guide & live (LIVE-1…11)
* **Guide.** Rows are channels in the selected group. The window is 30-minute slots from now−pastDays to now+futureDays, with initial focus on the current programme of the last-watched channel.
  * Left/Right move by programme, and Up/Down by channel.
  * The top bar shows the focused programme's title, time, description and a progress bar.
  * A vertical "now" line marks the current time.
* **Channel row.** Shows the number, logo, name, a catch-up badge and a lock badge.
* **Programme cell.** Shows the title and time. A past cell with catch-up available shows a ⟲ badge.
* **Activation:**
  * current or future programme → play live;
  * past programme with catch-up → play the archive URL from `CatchupUrlBuilder`;
  * a locked item → PIN prompt.
* **Player.**
  * OK toggles the channel overlay: a list of the current group, focused on the current channel. The info bar shows on zap and auto-hides after 4 s (configurable).
  * Back closes the overlay if one is open; otherwise it leaves the player.
  * A "Last channel" action toggles to the previous channel. A: dedicated key `KEYCODE_LAST_CHANNEL`, or double-Back within 1 s.
* **Reconnect.** On a player error or a stall longer than 10 s, the player retries with exponential backoff (1, 2, 4, 8, 16 s; at most 5 attempts) and shows "Reconnecting…".

### 5.4 VOD (VOD-1…6)
* **Movies home.** Rows: Continue watching, Favorites, then one row or grid per visible category, sorted by provider order. Item details are fetched lazily via `get_vod_info` and cached in memory.
* **Series.** `get_series_info` returns seasons and episodes. The episode list shows the image, title, duration and watched progress.
* **Progress.** Saved every 10 s and on exit. An item counts as watched past 92 %. Resume starts 5 s before the saved position.

### 5.5 Search (SRCH-1)
Searches channels, movies and series with a SQL `LIKE` over a normalised name column, plus programme titles for now…+24 h. Results are grouped by type and capped at 50 per type. Search starts after 2 characters, debounced by 300 ms.

### 5.6 Parental & settings (SET-1…3, 7, 8)
* The PIN is 4 digits, stored as a salted SHA-256 hash. Locked groups and channels require the PIN. After the PIN is entered, everything is unlocked for the session (until the app goes to the background).
* Settings: sources, guide window (past/future days), 12/24 h time, overlay timeout, auto-refresh interval, parental PIN set/change/clear, and an About screen with the license and source link.

## 6. Non-functional requirements
| Area | Requirement |
|------|-------------|
| Performance | Import 50k channels in under 10 s and a 200 MB XMLTV in under 60 s on a mid-range Android TV. The guide scrolls at 60 fps and the first frame of a channel appears in under 1.5 s on a good stream. |
| Memory | Parsing peak below 150 MB regardless of input size, using streamed parsers and batched writes. |
| Resilience | Malformed input never crashes the app: bad lines are skipped and counted. A network failure keeps the last good data. |
| Privacy | No analytics or third-party SDKs; network calls go only to user-configured URLs. |
| Accessibility | Visible focus everywhere (scale plus outline), text ≥ 14sp/24pt at 10-foot distance, and content descriptions or labels for VoiceOver/TalkBack. |
| i18n | Full Unicode, RTL-safe layouts, all strings in resource files (English in v1). |
| Security | Cleartext HTTP is allowed because providers need it; it is documented. Credentials never appear in logs. |

## 7. Testing
* **Shared:** unit tests (`commonTest`) run on JVM and the tvOS simulator.
  * M3U/XMLTV parsers, with chunk-boundary fuzzing.
  * Gzip.
  * Time parsing.
  * Catch-up URL templates.
  * The Xtream client against Ktor `MockEngine` with real-world quirky payloads.
  * The sync merge rule against an in-memory SQLite DB.
* **Fixture server.** `tools/fixture-server` is a small local HTTP server. It serves:
  * a generated M3U (5k channels);
  * XMLTV (.gz);
  * a fake Xtream API;
  * HLS test streams generated with ffmpeg.

  Emulators reach it through `10.0.2.2` on Android and `localhost` on the tvOS simulator, so the flows are tested end to end without a real provider.
* **UI smoke tests.** The Android TV emulator is driven over adb with D-pad key events and screenshots. The tvOS simulator is driven via `xcrun simctl` and XCUITest remote presses.

## 8. Milestones
1. **M1 Core:** parsers, Xtream client, DB, sync, EPG import, facade, tests.
2. **M2 Android TV:** onboarding, guide, player, groups, favorites, VOD, search, settings.
3. **M3 tvOS:** the same screens in SwiftUI on the same core.
4. **M4 Hardening:** fixture server, end-to-end runs on both emulators, performance pass, README and docs.
5. **v1.x:** phone-based setup, backup/sync, additional playback engine, reminders, manual EPG mapping.
