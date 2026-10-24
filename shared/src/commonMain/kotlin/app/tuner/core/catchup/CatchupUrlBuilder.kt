package app.tuner.core.catchup

import app.tuner.core.model.CatchupMode
import app.tuner.core.model.Channel
import app.tuner.core.xtream.XtreamClient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Builds catch-up (archive) URLs. M3U playlists describe the scheme via
 * `catchup`/`catchup-source` attributes with placeholders; Xtream servers use
 * the /timeshift/ path in the server's own time zone.
 */
object CatchupUrlBuilder {
    fun modeOf(raw: String?): CatchupMode = when (raw?.trim()?.lowercase()) {
        null, "" -> CatchupMode.NONE
        "default" -> CatchupMode.DEFAULT
        "append" -> CatchupMode.APPEND
        "shift", "timeshift" -> CatchupMode.SHIFT
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupMode.FLUSSONIC
        "xc" -> CatchupMode.XTREAM
        else -> CatchupMode.DEFAULT
    }

    /** Returns a playable URL for [startMs]..[stopMs] or null if the channel has no catch-up. */
    fun build(channel: Channel, startMs: Long, stopMs: Long, nowMs: Long, xtream: XtreamClient?, serverTimezone: String?): String? {
        val startSec = startMs / 1000
        val stopSec = stopMs / 1000
        val durationSec = (stopSec - startSec).coerceAtLeast(60)
        return when (channel.catchupMode) {
            CatchupMode.NONE -> null
            CatchupMode.XTREAM -> {
                val client = xtream ?: return null
                val id = channel.remoteId ?: return null
                val tz = serverTimezone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.UTC
                val t = Instant.fromEpochSeconds(startSec).toLocalDateTime(tz)
                val stamp = "${t.year}-${p2(t.month.ordinal + 1)}-${p2(t.day)}:${p2(t.hour)}-${p2(t.minute)}"
                val c = client.credentials
                // Match the live format: HLS channels (tvOS) get an HLS archive, others MPEG-TS.
                val ext = if (channel.url.substringBefore('?').endsWith(".m3u8")) "m3u8" else "ts"
                "${c.server}/timeshift/${XtreamClient.enc(c.username)}/${XtreamClient.enc(c.password)}/${(durationSec + 59) / 60}/$stamp/$id.$ext"
            }
            CatchupMode.SHIFT -> appendQuery(channel.url, "utc=$startSec&lutc=${nowMs / 1000}")
            CatchupMode.FLUSSONIC -> flussonic(channel.url, startSec, durationSec)
            CatchupMode.APPEND -> {
                val src = channel.catchupSource ?: return appendQuery(channel.url, "utc=$startSec&lutc=${nowMs / 1000}")
                channel.url + substitute(src, startSec, stopSec, nowMs / 1000)
            }
            CatchupMode.DEFAULT -> {
                val src = channel.catchupSource ?: return appendQuery(channel.url, "utc=$startSec&lutc=${nowMs / 1000}")
                substitute(src, startSec, stopSec, nowMs / 1000)
            }
        }
    }

    private fun flussonic(url: String, start: Long, duration: Long): String {
        // http://host/channel/index.m3u8 -> http://host/channel/index-<start>-<duration>.m3u8
        // http://host/channel/mpegts     -> http://host/channel/timeshift_abs-<start>.ts
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val file = base.substringAfterLast('/')
        val dir = base.substringBeforeLast('/')
        return when {
            file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8")}-$start-$duration.m3u8$query"
            file == "mpegts" -> "$dir/timeshift_abs-$start.ts$query"
            else -> "$dir/$file/index-$start-$duration.m3u8$query"
        }
    }

    internal fun substitute(template: String, start: Long, stop: Long, now: Long): String {
        val duration = stop - start
        val utc = Instant.fromEpochSeconds(start).toLocalDateTime(TimeZone.UTC)
        val end = Instant.fromEpochSeconds(stop).toLocalDateTime(TimeZone.UTC)
        var s = template
            .replace("{utc}", start.toString())
            .replace("\${start}", start.toString())
            .replace("{utcend}", stop.toString())
            .replace("\${end}", stop.toString())
            .replace("{lutc}", now.toString())
            .replace("\${now}", now.toString())
            .replace("\${timestamp}", now.toString())
            .replace("{duration}", duration.toString())
            .replace("\${duration}", duration.toString())
            .replace("{offset}", (now - start).toString())
            .replace("\${offset}", (now - start).toString())
            .replace("{Y}", utc.year.toString())
            .replace("{m}", p2(utc.month.ordinal + 1))
            .replace("{d}", p2(utc.day))
            .replace("{H}", p2(utc.hour))
            .replace("{M}", p2(utc.minute))
            .replace("{S}", p2(utc.second))
        // {duration:60} → duration divided by the given number of seconds (e.g. minutes)
        s = Regex("\\{duration:(\\d+)\\}").replace(s) { m -> (duration / m.groupValues[1].toLong().coerceAtLeast(1)).toString() }
        s = Regex("\\{(utc|start|utcend|end):([^}]+)\\}").replace(s) { m ->
            val t = if (m.groupValues[1] == "utc" || m.groupValues[1] == "start") utc else end
            m.groupValues[2]
                .replace("Y", t.year.toString()).replace("m", p2(t.month.ordinal + 1)).replace("d", p2(t.day))
                .replace("H", p2(t.hour)).replace("M", p2(t.minute)).replace("S", p2(t.second))
        }
        return s
    }

    private fun appendQuery(url: String, q: String) = if ('?' in url) "$url&$q" else "$url?$q"

    private fun p2(v: Int) = v.toString().padStart(2, '0')
}
