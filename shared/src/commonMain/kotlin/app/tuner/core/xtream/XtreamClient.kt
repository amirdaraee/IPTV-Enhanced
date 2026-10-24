package app.tuner.core.xtream

import app.tuner.core.model.ContentKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.TimeZone
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong

data class XtreamCredentials(val baseUrl: String, val username: String, val password: String) {
    /** Normalised "scheme://host[:port]" without trailing slash or player_api.php. */
    val server: String = normaliseServer(baseUrl)

    companion object {
        fun normaliseServer(url: String): String {
            var u = url.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            u = u.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
            return u
        }
    }
}

data class XtreamAccount(
    val status: String?,
    val expiresAt: Long?,        // epoch millis
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val outputFormats: List<String>,
    val serverTimezone: String?,
    /** Server wall-clock time ("yyyy-MM-dd HH:mm:ss") and its epoch seconds, for deriving an offset. */
    val serverTimeNow: String? = null,
    val serverTimestampNow: Long? = null,
) {
    /**
     * Time zone the panel uses for timeshift URLs: the declared zone if valid,
     * else a fixed offset derived from the server clock, else UTC.
     */
    fun resolvedTimezone(): String {
        serverTimezone?.let { tz -> if (runCatching { TimeZone.of(tz) }.isSuccess) return tz }
        val wall = serverTimeNow?.let(::parseWallClockAsUtc)
        val ts = serverTimestampNow
        if (wall != null && ts != null) {
            val minutes = ((wall - ts) / 60.0 / 15.0).roundToLong() * 15
            if (minutes != 0L && kotlin.math.abs(minutes) <= 14 * 60) {
                val sign = if (minutes < 0) "-" else "+"
                val m = kotlin.math.abs(minutes)
                return "UTC$sign${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
            }
        }
        return "UTC"
    }

    private fun parseWallClockAsUtc(s: String): Long? {
        val digits = s.filter { it.isDigit() }
        if (digits.length < 14) return null
        return app.tuner.core.xmltv.parseXmltvTime(digits.take(14) + " +0000")?.div(1000)
    }
}

data class XtreamCategory(val id: String, val name: String)

data class XtreamStream(
    val id: String,
    val num: Int?,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val added: Long?,
    val hasArchive: Boolean,
    val archiveDays: Int,
    val containerExtension: String?,
    val rating: Double?,
    val directSource: String?,
)

data class XtreamSeries(
    val id: String,
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val categoryId: String?,
    val lastModified: Long?,
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Long?,
)

data class XtreamSeriesInfo(
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val backdrop: String?,
    val episodes: List<XtreamEpisode>,
)

data class XtreamVodInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSecs: Long?,
    val backdrop: String?,
    val trailer: String?,
    val releaseDate: String?,
)

data class XtreamEpgEntry(val title: String, val description: String?, val start: Long, val stop: Long, val hasArchive: Boolean)

class XtreamException(message: String) : Exception(message)

/** Client for the de-facto Xtream Codes `player_api.php` API. */
class XtreamClient(
    private val http: HttpClient,
    val credentials: XtreamCredentials,
    private val userAgent: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun call(action: String?, vararg params: Pair<String, Any?>): JsonElement {
        val response = http.get("${credentials.server}/player_api.php") {
            parameter("username", credentials.username)
            parameter("password", credentials.password)
            if (action != null) parameter("action", action)
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            userAgent?.let { header(HttpHeaders.UserAgent, it) }
        }
        if (!response.status.isSuccess()) throw XtreamException("Server returned HTTP ${response.status.value}")
        val text = response.bodyAsText()
        return try {
            json.parseToJsonElement(text.ifBlank { "[]" })
        } catch (e: Exception) {
            throw XtreamException("Server did not return valid JSON")
        }
    }

    suspend fun authenticate(): XtreamAccount {
        val root = call(null) as? JsonObject ?: throw XtreamException("Unexpected login response")
        val user = root.obj("user_info") ?: throw XtreamException("Login failed: no account info")
        if (user.int("auth") == 0) throw XtreamException("Wrong username or password")
        val server = root.obj("server_info")
        val formats = (user["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        return XtreamAccount(
            status = user.str("status"),
            expiresAt = user.long("exp_date")?.let { it * 1000 },
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            isTrial = user.bool("is_trial"),
            outputFormats = formats,
            serverTimezone = server?.str("timezone"),
            serverTimeNow = server?.str("time_now"),
            serverTimestampNow = server?.long("timestamp_now"),
        )
    }

    suspend fun categories(kind: ContentKind): List<XtreamCategory> {
        val action = when (kind) {
            ContentKind.LIVE -> "get_live_categories"
            ContentKind.MOVIE -> "get_vod_categories"
            ContentKind.SERIES -> "get_series_categories"
        }
        return call(action).asObjects().mapNotNull { o ->
            val id = o.str("category_id") ?: return@mapNotNull null
            XtreamCategory(id, o.str("category_name") ?: id)
        }
    }

    suspend fun streams(kind: ContentKind): List<XtreamStream> {
        require(kind != ContentKind.SERIES)
        val action = if (kind == ContentKind.LIVE) "get_live_streams" else "get_vod_streams"
        return call(action).asObjects().mapNotNull { o ->
            val id = o.str("stream_id") ?: return@mapNotNull null
            XtreamStream(
                id = id,
                num = o.int("num"),
                name = o.str("name") ?: id,
                icon = o.str("stream_icon"),
                epgChannelId = o.str("epg_channel_id"),
                categoryId = o.str("category_id"),
                added = o.long("added")?.let { it * 1000 },
                hasArchive = o.bool("tv_archive"),
                archiveDays = o.int("tv_archive_duration") ?: 0,
                containerExtension = o.str("container_extension"),
                rating = o.double("rating"),
                directSource = o.str("direct_source"),
            )
        }
    }

    suspend fun series(): List<XtreamSeries> = call("get_series").asObjects().mapNotNull { o ->
        val id = o.str("series_id") ?: return@mapNotNull null
        XtreamSeries(
            id = id,
            name = o.str("name") ?: id,
            cover = o.str("cover"),
            plot = o.str("plot"),
            genre = o.str("genre"),
            releaseDate = o.str("releaseDate") ?: o.str("release_date"),
            rating = o.double("rating"),
            categoryId = o.str("category_id"),
            lastModified = o.long("last_modified")?.let { it * 1000 },
        )
    }

    suspend fun seriesInfo(seriesId: String): XtreamSeriesInfo {
        val root = call("get_series_info", "series_id" to seriesId) as? JsonObject
            ?: throw XtreamException("Series not found")
        val info = root.obj("info")
        val episodes = when (val e = root["episodes"]) {
            is JsonObject -> e.values.flatMap { it.asObjects() }
            is JsonArray -> e.flatMap { if (it is JsonArray) it.asObjects() else listOfNotNull(it as? JsonObject) }
            else -> emptyList()
        }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val epInfo = o.obj("info")
            XtreamEpisode(
                id = id,
                season = o.int("season") ?: epInfo?.int("season") ?: 1,
                episode = o.int("episode_num") ?: 0,
                title = o.str("title") ?: "Episode ${o.str("episode_num") ?: ""}".trim(),
                extension = o.str("container_extension") ?: "mp4",
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image"),
                durationSecs = epInfo?.long("duration_secs"),
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return XtreamSeriesInfo(
            plot = info?.str("plot"),
            cast = info?.str("cast"),
            genre = info?.str("genre"),
            backdrop = info?.firstString("backdrop_path"),
            episodes = episodes,
        )
    }

    suspend fun vodInfo(vodId: String): XtreamVodInfo {
        val root = call("get_vod_info", "vod_id" to vodId) as? JsonObject ?: throw XtreamException("Movie not found")
        val info = root.obj("info")
        return XtreamVodInfo(
            plot = info?.str("plot") ?: info?.str("description"),
            cast = info?.str("cast") ?: info?.str("actors"),
            director = info?.str("director"),
            genre = info?.str("genre"),
            durationSecs = info?.long("duration_secs"),
            backdrop = info?.firstString("backdrop_path"),
            trailer = info?.str("youtube_trailer"),
            releaseDate = info?.str("releasedate") ?: info?.str("release_date"),
        )
    }

    /** Short EPG for one channel; titles/descriptions are base64-encoded by the API. */
    suspend fun shortEpg(streamId: String, limit: Int = 10): List<XtreamEpgEntry> {
        val root = call("get_short_epg", "stream_id" to streamId, "limit" to limit) as? JsonObject ?: return emptyList()
        return root["epg_listings"].asObjects().mapNotNull { o ->
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val stop = o.long("stop_timestamp") ?: return@mapNotNull null
            XtreamEpgEntry(
                title = decodeBase64(o.str("title")) ?: "",
                description = decodeBase64(o.str("description")),
                start = start * 1000,
                stop = stop * 1000,
                hasArchive = o.bool("has_archive"),
            )
        }
    }

    val xmltvUrl: String
        get() = "${credentials.server}/xmltv.php?username=${enc(credentials.username)}&password=${enc(credentials.password)}"

    val m3uUrl: String
        get() = "${credentials.server}/get.php?username=${enc(credentials.username)}&password=${enc(credentials.password)}&type=m3u_plus&output=ts"

    fun streamUrl(kind: ContentKind, streamId: String, extension: String?): String {
        val u = enc(credentials.username)
        val p = enc(credentials.password)
        return when (kind) {
            ContentKind.LIVE -> "${credentials.server}/live/$u/$p/$streamId.${extension ?: "ts"}"
            ContentKind.MOVIE -> "${credentials.server}/movie/$u/$p/$streamId.${extension ?: "mp4"}"
            ContentKind.SERIES -> "${credentials.server}/series/$u/$p/$streamId.${extension ?: "mp4"}"
        }
    }

    private fun decodeBase64(s: String?): String? {
        if (s == null) return null
        return try {
            Base64.decode(s).decodeToString()
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    companion object {
        internal fun enc(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c)
                else append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
