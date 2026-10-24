package app.tuner.core

import app.tuner.core.model.ContentKind
import app.tuner.core.model.GroupType
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import app.tuner.core.platform.PlatformContext
import app.tuner.core.platform.tunerDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Imports real providers over the network. Skipped unless configured, so it never
 * runs in CI and credentials never live in the repo:
 *
 *   LIVE_M3U=<playlist url>  LIVE_XTREAM=<server>|<user>|<pass>  ./gradlew :shared:jvmTest --tests '*LiveSourceTest*'
 *
 * Writes a sample of resolved playback URLs to build/live-urls.txt for manual playback checks.
 */
class LiveSourceTest {
    private val out = StringBuilder()

    private fun core(): TunerCore {
        val db = File.createTempFile("tuner-live", ".db").apply { delete(); deleteOnExit() }
        val http = HttpClient(OkHttp) {
            tunerDefaults()
            install(ResponseObserver) { onResponse { r -> if (!r.status.isSuccess()) println("HTTP ${r.status.value} ${app.tuner.core.io.redact(r.call.request.url.toString())}") } }
        }
        return TunerCore(PlatformContext(db.path, http))
    }

    private fun report(core: TunerCore, label: String) = runBlocking {
        val rt = Runtime.getRuntime()
        println("[$label] heap used ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
        val groups = core.liveGroups(false)
        val all = core.channels(groups.first { it.type == GroupType.ALL }, false)
        val now = System.currentTimeMillis()
        val rows = core.guide(all, now, now + 3_600_000L)
        val withEpg = rows.count { it.programmes.isNotEmpty() }
        println("[$label] ${groups.size} groups, ${all.size} channels, $withEpg with guide data now")
        for (kind in listOf(ContentKind.MOVIE, ContentKind.SERIES)) {
            val g = core.vodGroups(kind, false)
            println("[$label] $kind: ${g.size} groups")
        }
        val ms = measureTimeMillis { core.search("news") }
        println("[$label] search 'news' ${ms} ms")
        all.filterIndexed { i, _ -> i % (all.size / 25 + 1) == 0 }.forEach {
            out.append("$label\t${it.name}\t${core.playChannel(it).url}\n")
        }
        rows.firstOrNull { r -> r.channel.catchupDays > 0 && r.programmes.isNotEmpty() }?.let { r ->
            val past = core.guide(listOf(r.channel), now - 3 * 3_600_000L, now - 3_600_000L).first().programmes.firstOrNull()
            if (past != null) out.append("$label-catchup\t${r.channel.name} / ${past.title}\t${core.playCatchup(r.channel, past)?.url}\n")
        }
    }

    private fun flush() = File("build/live-urls.txt").appendText(out.toString())

    @Test
    fun m3u() = runBlocking {
        val url = System.getenv("LIVE_M3U") ?: return@runBlocking
        val core = core()
        val id = core.addSource(SourceInput("Live M3U", SourceType.M3U, url))
        val ms = measureTimeMillis { assertNull(core.refreshNow(id)) }
        while (core.sourcesSyncing()) Thread.sleep(100)
        println("[m3u] sync ${ms} ms")
        report(core, "m3u")
        flush()
    }

    @Test
    fun xtream() = runBlocking {
        val cfg = System.getenv("LIVE_XTREAM")?.split('|') ?: return@runBlocking
        val core = core()
        val account = core.verifyXtream(cfg[0], cfg[1], cfg[2])
        println("[xtream] status=${account.status} tz=${account.resolvedTimezone()} max=${account.maxConnections}")
        val id = core.addSource(SourceInput("Live Xtream", SourceType.XTREAM, cfg[0], cfg[1], cfg[2]))
        val ms = measureTimeMillis { assertNull(core.refreshNow(id)) }
        while (core.sourcesSyncing()) Thread.sleep(100)
        println("[xtream] sync ${ms} ms")
        report(core, "xtream")
        val movies = core.vodGroups(ContentKind.MOVIE, false).first { it.type == GroupType.RECENTS }
        val movie = core.movies(movies, 0, 1).first()
        out.append("xtream-movie\t${movie.name}\t${core.playMovie(movie).url}\n")
        val series = core.series(core.vodGroups(ContentKind.SERIES, false).first { it.type == GroupType.CATEGORY }, 0, 1).first()
        val details = core.seriesDetails(series)
        assertTrue(details.seasons.isNotEmpty(), "series has seasons")
        flush()
    }
}
