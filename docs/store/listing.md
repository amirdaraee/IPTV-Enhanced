# Store listing

Text and assets for Google Play, the Amazon Appstore and the App Store. Keep it consistent with the [README](../../README.md) and the [privacy policy](../privacy.md): the app plays the user's own playlists, ships no content, and collects no data.

## Assets

| | Google Play / Amazon | App Store |
|-|----------------------|-----------|
| Screenshots | [`android-tv/`](android-tv/), 1920×1080 | [`apple-tv/`](apple-tv/), 3840×2160 |
| Icon | [`../brand/icon.png`](../brand/icon.png), 512×512 | from the app's asset catalog |
| TV banner | [`../brand/banner.png`](../brand/banner.png), 1280×720 | — |
| Feature graphic | [`feature-graphic.png`](feature-graphic.png), 1024×500 | — |

The screenshots show the fixture server's made-up showcase channels; see [development.md](../development.md#store-screenshots) to retake them.

## Text

**Name** (30): IPTV Enhanced

**Short description / subtitle** (80 / 30):
- Play Store: `A TV guide and player for your own M3U playlists and Xtream logins.`
- App Store subtitle: `Guide and player for playlists`

**Full description** (Play: 4000, App Store: 4000):

> IPTV Enhanced is a free, open-source TV guide and player for the playlists you already have.
>
> Add an M3U playlist with its XMLTV guide, or an Xtream login from your provider, and browse your channels in a full TV guide built for the remote. Preview a channel without leaving the guide, zap with up and down, and go back in time on channels that offer catch-up.
>
> • A grid TV guide with a live preview, groups, favorites and recents
> • Catch-up on supported channels
> • Movies and series with resume, "Up next" and favorites
> • Search across channels, programmes, movies and series
> • Preferred audio and subtitle languages
> • Parental PIN for channels, groups and settings
> • Handles very large playlists and guides smoothly
>
> No accounts, no ads and no tracking. Your playlists and settings stay on your TV.
>
> IPTV Enhanced does not provide any channels or content. You need a playlist or login from a provider, and you should only add playlists you are authorised to use.
>
> The source code is available under the GPL-3.0 licence: github.com/amirdaraee/IPTV-Enhanced

**App Store keywords** (100): `m3u,xmltv,epg,tv guide,playlist,player,xtream,live tv,catch-up,m3u8`

**Category:** Video Players & Editors (Play) · Entertainment (App Store)

**Privacy policy URL:** https://amirdaraee.com/IPTV-Enhanced/privacy.html

**Support:** apps@amirdaraee.com

## Review notes

For Google Play and App Review, so reviewers can try the app without a provider:

> IPTV Enhanced is a player for the user's own playlists and contains no content. To try it, choose "Add your playlist", keep "M3U playlist URL" selected and enter:
> https://raw.githubusercontent.com/amirdaraee/IPTV-Enhanced/main/docs/demo/playlist.m3u
> This demo playlist has open-licence films (Blender Foundation, CC BY) and Apple's sample streams, with a guide. No login is needed.

## Questionnaires

- **Data safety (Play) / App privacy (App Store):** no data collected or shared.
- **Content rating:** the app shows no content of its own. Answer as a media player whose content comes from the user.
- **Ads:** none.
- **Target audience:** not directed at children.
