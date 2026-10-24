package app.tuner.core

import app.tuner.core.model.ContentKind
import app.tuner.core.model.GroupType
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import app.tuner.core.platform.PlatformContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.TimeZone
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * Times sync and the queries behind every screen against a provider the size of a large
 * real account (21.7k channels, 103k movies, 25k series, 200k programmes), served by a
 * mock Xtream panel so runs are repeatable. Skipped unless BENCH_DB is set; the database
 * is built on first use and reused, and BENCH_SYNC=1 times a full re-sync first.
 *
 *   BENCH_DB=/tmp/bench.db ./gradlew :shared:jvmTest --tests '*BenchmarkTest*'
 */
class BenchmarkTest {
    @Test
    fun screens() = runBlocking {
        val path = System.getenv("BENCH_DB") ?: return@runBlocking
        val fresh = !File(path).exists()
        val core = TunerCore(PlatformContext(path, HttpClient(MockEngine { req -> respond(SyntheticPanel.respond(req.url.encodedPath, req.url.parameters["action"])) })))
        if (fresh) core.addSource(SourceInput("Synthetic", SourceType.XTREAM, "http://panel.test", "u", "p"))
        if (fresh || System.getenv("BENCH_SYNC") == "1") {
            val s = core.sources().first()
            var phase = ""
            var since = System.currentTimeMillis()
            val watch = core.watchSync { states ->
                val p = states.firstOrNull()?.phase ?: return@watchSync
                if (p != phase) {
                    val t = System.currentTimeMillis()
                    if (phase.isNotEmpty()) println("bench:   phase %-22s %6d ms".format(phase, t - since))
                    phase = p
                    since = t
                }
            }
            val ms = measureTimeMillis { println("bench: sync error=${core.refreshNow(s.id)}") }
            watch.cancel()
            println("bench: sync ${ms} ms")
        }

        val now = System.currentTimeMillis()
        val groups = core.liveGroups(false)
        val all = groups.first { it.type == GroupType.ALL }
        val allChannels = core.channels(all, false)
        val category = groups.first { it.type == GroupType.CATEGORY }
        val movieGroup = core.vodGroups(ContentKind.MOVIE, false).first { it.type == GroupType.CATEGORY }
        val seriesGroup = core.vodGroups(ContentKind.SERIES, false).first { it.type == GroupType.CATEGORY }
        println("bench: ${allChannels.size} channels in All")

        time("liveGroups") { core.liveGroups(false) }
        time("vodGroups movie") { core.vodGroups(ContentKind.MOVIE, false) }
        time("channels All") { core.channels(all, false) }
        time("channels category") { core.channels(category, false) }
        time("guide 30 rows 36h") { core.guide(allChannels.subList(1000, 1030), now - 12 * 3_600_000L, now + 24 * 3_600_000L) }
        time("upcoming") { core.upcoming(allChannels[1000], 10) }
        time("movies page") { core.movies(movieGroup, 0, 200) }
        time("movies page 10") { core.movies(movieGroup, 400, 200) }
        time("series page") { core.series(seriesGroup, 0, 200) }
        time("continueWatching") { core.continueWatching(30) }
        for (q in listOf("news", "fr", "the", "sport")) time("search '$q'") { core.search(q) }
        time("playChannel") { core.playChannel(allChannels[1000]) }
        time("lastChannel") { core.lastChannel() }
    }

    private suspend fun time(label: String, runs: Int = 7, block: suspend () -> Unit) {
        block() // warm up
        val samples = (1..runs).map { measureNanoTime { block() } / 1_000_000.0 }.sorted()
        println("bench: %-22s median %8.1f ms  min %8.1f ms".format(label, samples[runs / 2], samples.first()))
    }
}

/** Deterministic Xtream panel content at the scale of a large commercial provider. */
private object SyntheticPanel {
    private const val LIVE = 21_700
    private const val MOVIES = 103_000
    private const val SERIES = 25_000
    private const val EPG_CHANNELS = 2_000
    private val words = listOf("News", "Sport", "The", "Movie", "France", "Kids", "Music", "World", "Drama", "Live", "Cinema", "Family")

    private fun title(i: Int) = "${words[i % words.size]} ${words[(i / 7) % words.size]} $i"

    private fun categories(n: Int, prefix: String) =
        (1..n).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"$prefix ${title(it)}"}""" }

    private val cache = HashMap<String, String>()

    fun respond(path: String, action: String?): String = cache.getOrPut("$path|$action") {
        if (path.endsWith("xmltv.php")) return@getOrPut epg()
        when (action) {
            null -> """{"user_info":{"auth":1,"status":"Active","max_connections":"1","active_cons":"0","exp_date":"1900000000"},"server_info":{"timezone":"Europe/Amsterdam","timestamp_now":${System.currentTimeMillis() / 1000}}}"""
            "get_live_categories" -> categories(300, "LIVE")
            "get_vod_categories" -> categories(150, "VOD")
            "get_series_categories" -> categories(80, "SERIES")
            "get_live_streams" -> (0 until LIVE).joinToString(",", "[", "]") { i ->
                """{"num":${i + 1},"name":"${title(i)} HD","stream_type":"live","stream_id":${i + 1},"stream_icon":"http://logo.test/$i.png","epg_channel_id":${if (i % 10 < 9) "\"ch${i % EPG_CHANNELS}.test\"" else "null"},"added":"1700000000","category_id":"${i % 300 + 1}","tv_archive":${i % 3 == 0},"tv_archive_duration":${if (i % 3 == 0) 7 else 0}}"""
            }
            "get_vod_streams" -> (0 until MOVIES).joinToString(",", "[", "]") { i ->
                """{"num":${i + 1},"name":"${title(i)} (20${i % 25})","stream_type":"movie","stream_id":${i + 1},"stream_icon":"http://poster.test/$i.jpg","rating":"${i % 10}.5","added":"${1700000000 + i}","category_id":"${i % 150 + 1}","container_extension":"mkv"}"""
            }
            "get_series" -> (0 until SERIES).joinToString(",", "[", "]") { i ->
                """{"num":${i + 1},"name":"${title(i)}","series_id":${i + 1},"cover":"http://cover.test/$i.jpg","plot":"A plot about ${title(i)}.","genre":"Drama","releaseDate":"2020-01-01","rating":"7","category_id":"${i % 80 + 1}","last_modified":"1700000000"}"""
            }
            else -> "[]"
        }
    }

    private fun epg(): String {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = System.currentTimeMillis() / 3_600_000L * 3_600_000L - 36 * 3_600_000L
        val sb = StringBuilder("<?xml version=\"1.0\"?><tv>")
        for (c in 0 until EPG_CHANNELS) sb.append("<channel id=\"ch$c.test\"><display-name>${title(c)} HD</display-name></channel>")
        for (c in 0 until EPG_CHANNELS) {
            var t = start
            repeat(100) { p ->
                val e = t + 45 * 60_000L
                sb.append("<programme start=\"${fmt.format(t)}\" stop=\"${fmt.format(e)}\" channel=\"ch$c.test\">")
                    .append("<title>${title(p * 31 + c)}</title><desc>Episode $p of ${title(c)}: a longer description with some text.</desc><category>Cat</category></programme>")
                t = e
            }
        }
        return sb.append("</tv>").toString()
    }
}
