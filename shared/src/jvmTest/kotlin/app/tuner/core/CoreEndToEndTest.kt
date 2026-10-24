package app.tuner.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.ContentKind
import app.tuner.core.model.GroupType
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreEndToEndTest {
    private val now = System.currentTimeMillis()
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }
    private var liveNames = listOf("News One", "Sports HD")
    var seenUserAgents = mutableListOf<String?>()

    private fun xmltv(): ByteArray {
        val hour = 3_600_000L
        val base = now - now % hour
        val xml = buildString {
            append("<?xml version=\"1.0\"?><tv>")
            append("<channel id=\"news.one\"><display-name>News One</display-name></channel>")
            append("<channel id=\"sports\"><display-name>Sports</display-name></channel>")
            for (i in -3..3) {
                val s = fmt.format(base + i * hour)
                val e = fmt.format(base + (i + 1) * hour)
                append("<programme start=\"$s\" stop=\"$e\" channel=\"news.one\"><title>News $i</title></programme>")
                append("<programme start=\"$s\" stop=\"$e\" channel=\"sports\"><title>Match $i</title></programme>")
            }
            // far outside the retention window: must be dropped
            append("<programme start=\"20000101000000 +0000\" stop=\"20000101010000 +0000\" channel=\"sports\"><title>Old</title></programme>")
            append("</tv>")
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
        return out.toByteArray()
    }

    private fun engine() = MockEngine { req ->
        seenUserAgents += req.headers[HttpHeaders.UserAgent]
        val action = req.url.parameters["action"]
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            req.url.encodedPath.endsWith("xmltv.php") -> respond(xmltv(), HttpStatusCode.OK)
            req.url.encodedPath.endsWith("player_api.php") -> when (action) {
                null -> respond(
                    """{"user_info":{"auth":1,"status":"Active","exp_date":"1900000000","max_connections":"2","allowed_output_formats":["m3u8","ts"]},
                       "server_info":{"timezone":"Europe/Berlin","timestamp_now":1700000000,"time_now":"2023-11-14 23:13:20"}}""",
                    headers = json,
                )
                "get_live_categories" -> respond("""[{"category_id":"1","category_name":"News"},{"category_id":2,"category_name":"Sports"}]""", headers = json)
                "get_live_streams" -> respond(
                    liveNames.mapIndexed { i, n ->
                        """{"num":${i + 1},"name":"$n","stream_id":"${100 + i}","stream_icon":"","epg_channel_id":${if (i == 0) "\"NEWS.ONE\"" else "null"},
                           "category_id":"${i + 1}","tv_archive":${if (i == 0) 1 else 0},"tv_archive_duration":"3"}"""
                    }.joinToString(",", "[", "]"),
                    headers = json,
                )
                "get_vod_categories" -> respond("""[{"category_id":"10","category_name":"Films"}]""", headers = json)
                "get_vod_streams" -> respond("""[{"name":"Big Film","stream_id":500,"category_id":"10","container_extension":"mkv","rating":"7.5","added":"1690000000"}]""", headers = json)
                "get_series_categories" -> respond("""[]""", headers = json)
                "get_series" -> respond("""[{"series_id":"900","name":"Show","category_id":null,"cover":"http://c/x.jpg"}]""", headers = json)
                "get_series_info" -> respond(
                    """{"info":{"plot":"A show"},"episodes":{"1":[{"id":"9001","episode_num":"1","title":"Pilot","container_extension":"mp4","season":1}],
                       "2":[{"id":"9002","episode_num":2,"title":"Two","container_extension":"mp4","info":{"season":"2"}}]}}""",
                    headers = json,
                )
                "get_vod_info" -> respond("""{"info":{"plot":"Plot!","duration_secs":"5400","backdrop_path":["http://b/1.jpg"]}}""", headers = json)
                else -> respond("[]", headers = json)
            }
            req.url.encodedPath.endsWith("playlist.m3u") -> respond(
                "#EXTM3U x-tvg-url=\"http://epg.test/xmltv.php\"\n#EXTINF:-1 tvg-id=\"news.one\" group-title=\"Info\",News One\nhttp://m3u/1.ts\n" +
                    "#EXTINF:-1 group-title=\"Info\",Sports\nhttp://m3u/2.ts\n",
            )
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun core(): TunerCore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TunerDatabase.Schema.create(driver)
        return TunerCore(driver, HttpClient(engine()))
    }

    @Test
    fun xtreamEndToEnd() = runBlocking {
        val core = core()
        val account = core.verifyXtream("demo.test:8080/player_api.php", "u", "p w")
        assertEquals("Europe/Berlin", account.resolvedTimezone())
        val id = core.addSource(SourceInput("Demo", SourceType.XTREAM, "demo.test:8080", "u", "p w", userAgent = "MyUA"))
        assertNull(core.refreshNow(id), "sync should succeed")
        // addSource also kicked off a background refresh; make sure it's finished
        while (core.sourcesSyncing()) Thread.sleep(20)

        val source = core.source(id)!!
        assertEquals("http://demo.test:8080", source.url)
        assertTrue("MyUA" in seenUserAgents)

        val groups = core.liveGroups(false)
        assertEquals(listOf("Favorites", "Recent", "All channels", "News", "Sports"), groups.map { it.name })
        val news = core.channels(groups.first { it.name == "News" }, false).single()
        assertEquals("http://demo.test:8080/live/u/p%20w/100.ts", news.url)
        assertEquals("news.one", news.epgKey)
        val sports = core.channels(groups.first { it.name == "Sports" }, false).single()
        assertEquals("sports", sports.epgKey, "name-based EPG fallback (Sports HD -> Sports)")

        val rows = core.guide(listOf(news, sports), now - 2 * 3_600_000L, now + 2 * 3_600_000L)
        assertEquals(5, rows[0].programmes.size)
        assertTrue(rows[1].programmes.none { it.title == "Old" })

        // favorites survive a re-sync with changed channel names
        core.setFavorite(ContentKind.LIVE, id, news.key, true)
        liveNames = listOf("News One (renamed)", "Sports HD")
        assertNull(core.refreshNow(id))
        val fav = core.channels(groups.first { it.type == GroupType.FAVORITES }, false).single()
        assertEquals("News One (renamed)", fav.name)

        // catch-up for a programme two hours ago, in the server's time zone
        val past = core.guide(listOf(fav), now - 3 * 3_600_000L, now).first().programmes.first()
        val catchup = core.playCatchup(fav, past)
        assertNotNull(catchup)
        assertTrue(catchup.url.startsWith("http://demo.test:8080/timeshift/u/p%20w/60/"), catchup.url)
        val sportsNow = core.channels(groups.first { it.name == "Sports" }, false).single()
        assertNull(core.playCatchup(sportsNow, past), "no archive on this channel")

        // recents + last channel
        core.playChannel(sportsNow)
        core.playChannel(fav)
        assertEquals(sportsNow.id, core.previousChannel()?.id)
        assertEquals(fav.id, core.lastChannel()?.id)
        assertEquals(2, core.channels(groups.first { it.type == GroupType.RECENTS }, false).size)

        // VOD
        val movieGroup = core.vodGroups(ContentKind.MOVIE, false).first { it.name == "Films" }
        val movie = core.movies(movieGroup, 0, 50).single()
        assertEquals("http://demo.test:8080/movie/u/p%20w/500.mkv", movie.url)
        val details = core.movieDetails(movie)
        assertEquals("Plot!", details.plot)
        val req = core.playMovie(movie)
        core.saveProgress(req, 600_000, 5_400_000)
        assertEquals(595_000, core.playMovie(movie).startPositionMs)
        assertEquals(1, core.continueWatching(10).size)

        val seriesGroup = core.vodGroups(ContentKind.SERIES, false).first { it.name == "Uncategorized" }
        val show = core.series(seriesGroup, 0, 50).single()
        val sd = core.seriesDetails(show)
        assertEquals(listOf(1, 2), sd.seasons)
        assertEquals("http://demo.test:8080/series/u/p%20w/9002.mp4", sd.episodes[1].url)

        // search across types
        val results = core.search("news")
        assertEquals(1, results.channels.size)
        assertTrue(results.programmes.isNotEmpty())
        assertEquals(1, core.search("big").movies.size)

        // hide group
        core.setGroupHidden(groups.first { it.name == "Sports" }, true)
        assertFalse(core.liveGroups(false).any { it.name == "Sports" })
        assertTrue(core.liveGroups(true).any { it.name == "Sports" && it.hidden })

        // pin
        assertTrue(core.isAllowed(true))
        core.setPin("1234")
        assertFalse(core.isAllowed(true))
        assertFalse(core.unlock("0000"))
        assertTrue(core.unlock("1234"))
        assertTrue(core.isAllowed(true))

        // a locked group locks its channels everywhere (All, search) and play is refused
        core.lockSession()
        val newsGroup = core.liveGroups(false).first { it.name == "News" }
        core.setGroupLocked(newsGroup, true)
        val lockedNews = core.search("news").channels.single()
        assertTrue(lockedNews.locked)
        assertTrue(core.channels(core.liveGroups(false).first { it.type == GroupType.ALL }, false).first { it.id == lockedNews.id }.locked)
        assertFailsWith<LockedException> { core.playChannel(lockedNews) }
        core.unlock("1234")
        core.playChannel(lockedNews)

        core.deleteSource(id)
        assertTrue(core.sources().isEmpty())
        assertEquals(3, core.liveGroups(false).size)
    }

    @Test
    fun m3uWithHeaderEpg() = runBlocking {
        val core = core()
        val id = core.addSource(SourceInput("List", SourceType.M3U, "http://m3u.test/playlist.m3u"))
        assertNull(core.refreshNow(id))
        while (core.sourcesSyncing()) Thread.sleep(20)
        val group = core.liveGroups(false).first { it.name == "Info" }
        val channels = core.channels(group, false)
        assertEquals(listOf("News One", "Sports"), channels.map { it.name })
        assertEquals(listOf(1, 2), channels.map { it.number })
        assertEquals(listOf("news.one", "sports"), channels.map { it.epgKey })
        assertEquals(listOf("http://epg.test/xmltv.php"), core.source(id)!!.autoEpgUrls)
    }
}
