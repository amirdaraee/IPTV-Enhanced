package app.tuner.core.model

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

enum class SourceType { M3U, XTREAM }

enum class ContentKind { LIVE, MOVIE, SERIES }

/**
 * How catch-up URLs are built for a channel, from the M3U `catchup` attribute or the Xtream API:
 * DEFAULT uses the playlist's `catchup-source` template as is, APPEND adds it to the live URL,
 * SHIFT adds `utc`/`lutc` query parameters, FLUSSONIC uses the `<channel>-<start>-<duration>`
 * path form, and XTREAM uses the panel's `timeshift` endpoint.
 */
enum class CatchupMode { NONE, DEFAULT, APPEND, SHIFT, FLUSSONIC, XTREAM }

data class Source(
    val id: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String?,
    val password: String?,
    /** User-supplied EPG URLs. */
    val epgUrls: List<String>,
    /** EPG URLs discovered from the playlist header or Xtream. */
    val autoEpgUrls: List<String>,
    val userAgent: String?,
    val epgOffsetMinutes: Int,
    val refreshHours: Int,
    val lastRefreshed: Long?,
    val lastError: String?,
    val enabled: Boolean,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val serverTimezone: String?,
)

data class SourceInput(
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrls: List<String> = emptyList(),
    val userAgent: String? = null,
    val epgOffsetMinutes: Int = 0,
    val refreshHours: Int = 24,
    val enabled: Boolean = true,
)

enum class GroupType { FAVORITES, RECENTS, ALL, CATEGORY }

@OptIn(ExperimentalObjCName::class)
@ObjCName("ChannelGroup", exact = true)
data class Group(
    /** Stable id usable for persistence: "fav", "recent", "all" or "c:<source>:<kind>:<key>". */
    val id: String,
    val type: GroupType,
    val kind: ContentKind,
    val sourceId: Long,
    val key: String,
    val name: String,
    val sourceName: String?,
    val hidden: Boolean,
    val locked: Boolean,
)

data class Channel(
    val id: Long,
    val sourceId: Long,
    val key: String,
    val categoryKey: String,
    val name: String,
    val number: Int?,
    val logo: String?,
    val epgId: String?,
    val epgKey: String?,
    val url: String,
    val remoteId: String?,
    val catchupMode: CatchupMode,
    val catchupDays: Int,
    val catchupSource: String?,
    val userAgent: String?,
    val referrer: String?,
    val favorite: Boolean,
    val hidden: Boolean,
    val locked: Boolean,
) {
    val hasCatchup: Boolean get() = catchupMode != CatchupMode.NONE && catchupDays > 0
}

data class Movie(
    val id: Long,
    val sourceId: Long,
    val key: String,
    val categoryKey: String,
    val name: String,
    val poster: String?,
    val url: String,
    val remoteId: String?,
    val rating: Double?,
    val year: String?,
    val addedAt: Long?,
    val favorite: Boolean,
    val locked: Boolean,
)

data class Series(
    val id: Long,
    val sourceId: Long,
    val key: String,
    val categoryKey: String,
    val name: String,
    val cover: String?,
    val remoteId: String,
    val plot: String?,
    val genre: String?,
    val rating: Double?,
    val year: String?,
    val favorite: Boolean,
    val locked: Boolean,
)

data class Episode(
    val remoteId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Long?,
    val url: String,
    val progress: WatchProgress?,
)

data class SeriesDetails(
    val series: Series,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val backdrop: String?,
    val seasons: List<Int>,
    val episodes: List<Episode>,
)

data class MovieDetails(
    val movie: Movie,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSecs: Long?,
    val backdrop: String?,
    val trailer: String?,
    val progress: WatchProgress?,
)

data class Programme(
    val channelEpgId: String,
    val start: Long,      // epoch millis
    val stop: Long,       // epoch millis
    val title: String,
    val description: String?,
    val category: String?,
    val icon: String?,
    val episodeNum: String? = null,
) {
    fun isLive(now: Long) = now in start until stop
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

data class GuideRow(val channel: Channel, val programmes: List<Programme>)

data class ProgrammeHit(val programme: Programme, val channel: Channel)

data class SearchResults(
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
    val programmes: List<ProgrammeHit>,
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && programmes.isEmpty()
}

data class WatchProgress(
    val key: String,          // "movie:<source>:<id>" or "episode:<source>:<id>"
    val sourceId: Long,
    val kind: ContentKind,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val parentKey: String?,   // series key for episodes
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val watched: Boolean get() = durationMs > 0 && positionMs >= durationMs * 92 / 100
}

/** Stream request with headers, ready for a platform player. */
data class PlaybackRequest(
    val url: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val userAgent: String?,
    val referrer: String?,
    val isLive: Boolean,
    val startPositionMs: Long,
    /** When set, the player should report progress with [app.tuner.core.TunerCore.saveProgress]. */
    val progressKey: String?,
    val progressParentKey: String?,
    val sourceId: Long,
    val kind: ContentKind,
    val channelId: Long?,
)

data class SyncState(
    val sourceId: Long,
    val running: Boolean,
    val phase: String,
    val count: Int,
    val error: String?,
)

@OptIn(ExperimentalObjCName::class)
@ObjCName("TunerSettings", exact = true)
data class Settings(
    val guidePastDays: Int = 2,
    val guideFutureDays: Int = 7,
    val use24h: Boolean = true,
    val overlayTimeoutSec: Int = 4,
    val autoplayLastChannel: Boolean = false,
    val preferHlsLive: Boolean = false,
    /** Preferred audio language (ISO 639-1, e.g. "fa"); empty = the stream's default. */
    val audioLanguage: String = "",
    /** Subtitle language to show automatically (ISO 639-1); empty = subtitles off. */
    val subtitleLanguage: String = "",
    /** Play the next episode after a short countdown when one ends. */
    val autoplayNextEpisode: Boolean = true,
)
