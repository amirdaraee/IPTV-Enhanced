package app.tuner.core.model

enum class SourceType { M3U, XTREAM }

enum class ContentKind { LIVE, MOVIE, SERIES }

/** How catch-up URLs are built for a channel. */
enum class CatchupMode { NONE, DEFAULT, APPEND, SHIFT, FLUSSONIC, XTREAM }

data class Source(
    val id: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val userAgent: String? = null,
    val refreshHours: Int = 24,
    val lastRefreshed: Long? = null,
    val lastError: String? = null,
    val enabled: Boolean = true,
)

data class Category(
    val id: Long,
    val sourceId: Long,
    val kind: ContentKind,
    val remoteId: String,
    val name: String,
    val hidden: Boolean,
    val sortOrder: Int,
)

data class Channel(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val groupName: String,
    val name: String,
    val number: Int?,
    val logo: String?,
    val epgId: String?,
    val url: String,
    val remoteId: String?,
    val catchupMode: CatchupMode,
    val catchupDays: Int,
    val catchupSource: String?,
    val userAgent: String?,
    val referrer: String?,
    val favorite: Boolean,
    val hidden: Boolean,
)

data class Movie(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val groupName: String,
    val name: String,
    val poster: String?,
    val url: String,
    val remoteId: String?,
    val rating: Double?,
    val year: String?,
    val addedAt: Long?,
    val favorite: Boolean,
)

data class Series(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val groupName: String,
    val name: String,
    val cover: String?,
    val remoteId: String,
    val plot: String?,
    val rating: Double?,
    val year: String?,
    val favorite: Boolean,
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
)

data class WatchProgress(
    val key: String,          // e.g. "movie:12", "episode:3:4512"
    val kind: ContentKind,
    val title: String,
    val image: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val parentId: Long?,      // series id for episodes
)

/** Stream request with headers, ready for a platform player. */
data class PlaybackRequest(
    val url: String,
    val title: String,
    val userAgent: String?,
    val referrer: String?,
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean,
    val startPositionMs: Long = 0,
    val progressKey: String? = null,
)
