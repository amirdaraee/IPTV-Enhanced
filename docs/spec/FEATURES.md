# IPTV Enhanced: Feature List

Legend: **P0** = v1.0 (this build), **P1** = v1.x, **P2** = later. Platform: A = Android TV, T = tvOS, both if blank.

## 1. Sources
| ID | Feature | Pri |
|----|---------|-----|
| SRC-1 | Add an M3U/M3U8 playlist by URL (gzip-aware, any size, streamed) | P0 |
| SRC-2 | Add an Xtream Codes account (server, username, password) with account status and expiry | P0 |
| SRC-3 | Unlimited sources, each enabled/disabled independently, all free | P0 |
| SRC-4 | Refresh on app start when stale, plus a per-source interval and manual refresh | P0 |
| SRC-5 | Per-source custom User-Agent; honour `#EXTVLCOPT`, `#KODIPROP` and `url\|Header=` | P0 |
| SRC-6 | Edit and delete sources | P0 |
| SRC-7 | Enter URLs and credentials from a phone (QR code plus a LAN web form served by the TV) | P1 |
| SRC-8 | Import a local M3U file (A: system file picker) | P1 |
| SRC-9 | Stalker/MAG portals, HDHomeRun | P2 |

## 2. EPG (XMLTV)
| ID | Feature | Pri |
|----|---------|-----|
| EPG-1 | XMLTV by URL, `.gz` supported, streamed into the database without loading the file in memory | P0 |
| EPG-2 | Automatic EPG: `url-tvg`/`x-tvg-url` from the M3U header, `xmltv.php` for Xtream | P0 |
| EPG-3 | Extra EPG URLs per source | P0 |
| EPG-4 | Channel matching: `tvg-id`, then a normalised display-name fallback | P0 |
| EPG-5 | Time offset per source (±hours) | P0 |
| EPG-6 | Retention window (keep past N days, future M days) | P0 |
| EPG-7 | Manual EPG assignment per channel, with search | P1 |
| EPG-8 | Programme reminders | P1 |

## 3. Live TV
| ID | Feature | Pri |
|----|---------|-----|
| LIVE-1 | Grid TV guide (channels × time) as the home screen, with a "now" line and focus details | P0 |
| LIVE-2 | Live preview keeps playing while browsing the guide | P0 (A), P1 (T) |
| LIVE-3 | Group sidebar with Favorites and Recents as first-class groups | P0 |
| LIVE-4 | Fullscreen player with an info bar (now/next, progress, channel number, logo) | P0 |
| LIVE-5 | Channel up/down zapping in the player (D-pad up/down; A: CH± keys) | P0 |
| LIVE-6 | Channel-list overlay inside the player (no playback interruption) | P0 |
| LIVE-7 | Last-channel recall (toggle between the two most recent channels) and a Recents list | P0 |
| LIVE-8 | Favorites: add/remove via long-press, shown first | P0 |
| LIVE-9 | Hide and reorder groups; hide channels | P0 |
| LIVE-10 | Channel numbers (`tvg-chno`, else sequential); A: number-key entry | P0 |
| LIVE-11 | Catch-up from the guide (Xtream `tv_archive`, M3U `catchup*` schemes) | P0 |
| LIVE-12 | Auto-start the last channel on launch (option) | P0 |
| LIVE-13 | Rename and reorder channels | P1 |
| LIVE-14 | Multiview, PiP | P2 |
| LIVE-15 | Clock in the guide and player; Play/Pause jumps the guide to now; "Earlier programmes" for catch-up browsing | P0 |
| LIVE-16 | Reorder Favorites (move up/down) | P0 |
| LIVE-17 | Sleep timer in the players (15 min – 2 h) | P0 |

## 4. Movies & Series (VOD)
| ID | Feature | Pri |
|----|---------|-----|
| VOD-1 | Movie categories with poster grids (Xtream, plus M3U entries classified as VOD) | P0 |
| VOD-2 | Movie details: plot, cast, genre, duration, backdrop | P0 |
| VOD-3 | Series categories, then series details with a season selector and episode list | P0 |
| VOD-4 | Resume position and a "Continue watching" row; watched markers | P0 |
| VOD-5 | Favorites for movies and series | P0 |
| VOD-6 | Seek bar with ±10 s / ±30 s skip; next episode | P0 |
| VOD-7 | TMDb enrichment with a user-supplied key | P2 |
| VOD-8 | "Up next" countdown that plays the next episode (option) | P0 |

## 5. Player
| ID | Feature | Pri |
|----|---------|-----|
| PLY-1 | Hardware playback of HLS, MPEG-TS, MP4 and MKV (A: Media3; T: AVPlayer) | P0 |
| PLY-2 | Audio and subtitle track selection | P0 |
| PLY-3 | Aspect modes: fit, fill, zoom | P0 |
| PLY-4 | Auto-reconnect with backoff on stream errors or stalls | P0 |
| PLY-5 | Custom headers (User-Agent/Referer) on every request | P0 |
| PLY-6 | Fallback engine for formats AVPlayer can't handle (T: raw MPEG-TS, MKV) via an additional playback engine | P1 |
| PLY-7 | Buffer size and audio-delay settings; frame-rate matching | P1 |
| PLY-8 | External player hand-off | P1 |
| PLY-9 | Preferred audio and subtitle language | P0 |

## 6. Search
| ID | Feature | Pri |
|----|---------|-----|
| SRCH-1 | Unified search across channels, movies, series and EPG programme titles | P0 |
| SRCH-2 | Recent searches (kept when a result is opened) | P0 |

## 7. Personalisation, safety and data
| ID | Feature | Pri |
|----|---------|-----|
| SET-1 | Parental PIN to lock groups or channels and protect settings (free) | P0 |
| SET-2 | Long-press context menu everywhere (favorite, hide, guide, catch-up, lock) | P0 |
| SET-3 | Settings: guide past/future window, time format, overlay timeout, auto-refresh | P0 |
| SET-4 | Backup and restore of sources, favorites, hidden items and progress as JSON | P1 |
| SET-5 | User-owned cross-device sync (iCloud KVS on T, file/WebDAV on A) | P1 |
| SET-6 | Themes and accent colour | P2 |
| SET-7 | All data stays on the device; no analytics, no accounts | P0 |
| SET-8 | Exit confirmation on Back from the home screen (A) | P0 |
| SET-9 | Subscription-expiry reminder (Xtream, 7 days ahead) | P0 |

## 8. Remote-control grammar (stable, documented, never changed silently)
| Context | OK | Long-press OK | Back | Up/Down | Left/Right |
|---------|----|---------------|------|---------|-----------|
| Guide | Channel name: play live fullscreen; programme: preview / catch-up | Context menu | Programme → channel name → group sidebar → exit | Channels | Group sidebar ↔ channel names ↔ time |
| Player (live) | Toggle channel overlay | Context menu | Hide overlay, then go back | Zap channel | Show info bar |
| Player (VOD) | Play/pause + controls | Tracks menu | Back | Show controls | Seek |
| Grids | Open | Context menu | Back | Move | Move |

On tvOS the Siri Remote maps OK→click, long-press→long click, Back→Menu/Back button, and swipes→D-pad.
