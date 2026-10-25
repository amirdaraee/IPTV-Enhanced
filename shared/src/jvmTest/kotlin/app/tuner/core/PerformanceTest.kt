package app.tuner.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.GroupType
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Large-provider import: 50k channels and ~200k programmes, timed against the SPEC targets. */
class PerformanceTest {
    private val channels = 50_000
    private val epgChannels = 2_000
    private val programmesPerChannel = 100

    private val playlist: ByteArray by lazy {
        val sb = StringBuilder("#EXTM3U url-tvg=\"http://p.test/epg.xml.gz\"\n")
        for (i in 0 until channels) {
            sb.append("#EXTINF:-1 tvg-id=\"ch$i.test\" tvg-logo=\"http://logo.test/$i.png\" group-title=\"Group ${i % 200}\",Channel $i HD\n")
            sb.append("http://stream.test/live/u/p/$i.ts\n")
        }
        gzip(sb.toString().toByteArray())
    }

    private val epg: ByteArray by lazy {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = System.currentTimeMillis() - 24 * 3_600_000L
        val sb = StringBuilder("<?xml version=\"1.0\"?><tv>")
        for (c in 0 until epgChannels) sb.append("<channel id=\"ch$c.test\"><display-name>Channel $c</display-name></channel>")
        for (c in 0 until epgChannels) {
            var t = start
            repeat(programmesPerChannel) { p ->
                val e = t + 30 * 60_000L
                sb.append("<programme start=\"${fmt.format(t)}\" stop=\"${fmt.format(e)}\" channel=\"ch$c.test\">")
                    .append("<title>Show $p on $c</title><desc>Description of show $p with some text &amp; entities.</desc><category>Cat</category></programme>")
                t = e
            }
        }
        sb.append("</tv>")
        gzip(sb.toString().toByteArray())
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    @Test
    fun importsLargeProvider() = runBlocking {
        println("playlist gz=${playlist.size / 1024} KB, epg gz=${epg.size / 1024} KB")
        val http = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("epg.xml.gz")) respond(epg) else respond(playlist)
        })
        val dbFile = kotlin.io.path.createTempFile("tuner-perf", ".db").toFile().also { it.deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        TunerDatabase.Schema.create(driver)
        val core = TunerCore(driver, http)
        val heapBefore = usedMb()
        var id = 0L
        val ms = measureTimeMillis {
            id = core.addSource(SourceInput("Big", SourceType.M3U, "http://p.test/list.m3u.gz"))
            assertNull(core.refreshNow(id))
            while (core.sourcesSyncing()) Thread.sleep(50)
        }
        println("full sync (50k channels + ${epgChannels * programmesPerChannel} programmes): $ms ms, heap delta ~${usedMb() - heapBefore} MB")
        val all = core.liveGroups(false).first { it.type == GroupType.ALL }
        val list = core.channels(all, false)
        assertEquals(50_000, list.size) // "All channels" is capped
        val rows = core.guide(list.take(30), System.currentTimeMillis() - 3_600_000, System.currentTimeMillis() + 3 * 3_600_000)
        assertTrue(rows.all { it.programmes.isNotEmpty() })
        val searchMs = measureTimeMillis { core.search("channel 4242") }
        println("search: $searchMs ms")
        assertTrue(ms < 60_000, "import took $ms ms")
        assertTrue(searchMs < 1_000, "search took $searchMs ms")
    }

    private fun usedMb(): Long {
        System.gc()
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024)
    }
}
