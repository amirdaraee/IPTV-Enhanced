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
    fun userDataSurvivesLosingTheDatabase() = runBlocking {
        val store = object : UserDataBackup {
            @Volatile var saved: String? = null
            override fun save(snapshot: String) { saved = snapshot }
            override fun load() = saved
        }
        fun freshDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TunerDatabase.Schema.create(it) }

        val a = TunerCore(freshDriver(), HttpClient(engine()), store)
        val id = a.addSource(SourceInput("Demo", SourceType.XTREAM, "demo.test:8080", "u", "p w"))
        assertNull(a.refreshNow(id))
        while (a.sourcesSyncing()) Thread.sleep(20)
        val groups = a.liveGroups(false)
        val news = a.channels(groups.first { it.name == "News" }, false).single()
        a.setFavorite(ContentKind.LIVE, id, news.key, true)
        a.setGroupHidden(groups.first { it.name == "Sports" }, true)
        val film = a.movies(a.vodGroups(ContentKind.MOVIE, false).first { it.type == GroupType.CATEGORY }, 0, 10).single()
        a.saveProgress(a.playMovie(film), 600_000, 5_400_000)
        a.updateSettings(a.settings.copy(use24h = false))
        // The backup is written once changes settle.
        val deadline = System.currentTimeMillis() + 5_000
        while (store.saved?.contains("\"use_24h\"") != true && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNotNull(store.saved)
        assertEquals(a.exportUserData(), store.saved, "the backup holds the latest data")
        a.close()

        // The database is gone (tvOS purged Caches, or a new install restored from Google).
        val b = TunerCore(freshDriver(), HttpClient(engine()), store)
        assertEquals(1, b.restoredSources)
        val restored = b.sources().single()
        assertEquals("Demo", restored.name)
        assertEquals("p w", restored.password)
        assertFalse(b.settings.use24h)
        assertNull(b.refreshNow(restored.id))
        val after = b.liveGroups(false)
        assertEquals(listOf("News One"), b.channels(after.first { it.type == GroupType.FAVORITES }, false).map { it.name })
        assertTrue(after.none { it.name == "Sports" }, "hidden group stays hidden")
        val resume = b.continueWatching(10).single()
        assertEquals("Big Film", resume.title)
        assertEquals(600_000, resume.positionMs)
        assertTrue(resume.key.startsWith("movie:${restored.id}:"), resume.key)

        // A backup with playlists never overwrites a database that already has some.
        assertEquals(0, TunerCore(freshDriver().also { d ->
            TunerCore(d, HttpClient(engine())).addSource(SourceInput("Other", SourceType.M3U, "http://m3u/playlist.m3u"))
        }, HttpClient(engine()), store).restoredSources)
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
        assertFailsWith<LockedException> { core.channels(newsGroup, false) }
        core.unlock("1234")
        core.playChannel(lockedNews)

        // continue-watching resume honours locks
        core.lockSession()
        core.setGroupLocked(movieGroup, true)
        val resume = core.continueWatching(10).single()
        assertFailsWith<LockedException> { core.playProgress(resume) }
        core.unlock("1234")
        assertEquals(595_000, core.playProgress(resume).startPositionMs)

        // PIN brute-force lockout
        core.lockSession()
        repeat(5) { assertFalse(core.unlock("9999")) }
        assertTrue(core.pinLockoutRemainingMs() > 0)
        assertFalse(core.unlock("1234"), "even the right PIN is refused during lockout")

        core.deleteSource(id)
        assertTrue(core.sources().isEmpty())
        assertEquals(3, core.liveGroups(false).size)
    }

    @Test
    fun conveniences() = runBlocking {
        val core = core()
        val id = core.addSource(SourceInput("Demo", SourceType.XTREAM, "demo.test:8080", "u", "p w"))
        assertNull(core.refreshNow(id))
        while (core.sourcesSyncing()) Thread.sleep(20)

        // Favorites can be reordered; a channel favorited afterwards goes to the end.
        val all = core.channels(core.liveGroups(false).first { it.type == GroupType.ALL }, false)
        val (news, sports) = all.partition { it.name.startsWith("News") }.let { it.first.single() to it.second.single() }
        core.setFavorite(ContentKind.LIVE, id, news.key, true)
        core.setFavorite(ContentKind.LIVE, id, sports.key, true)
        val favGroup = core.liveGroups(false).first { it.type == GroupType.FAVORITES }
        assertEquals(listOf(news.id, sports.id), core.channels(favGroup, false).map { it.id })
        core.moveFavorite(sports, -1)
        assertEquals(listOf(sports.id, news.id), core.channels(favGroup, false).map { it.id })
        core.moveFavorite(sports, -1)
        assertEquals(listOf(sports.id, news.id), core.channels(favGroup, false).map { it.id }, "already first")

        // Recent searches: newest first, no duplicates, short queries ignored.
        listOf("film", "news", "x", "FILM").forEach(core::rememberSearch)
        assertEquals(listOf("FILM", "news"), core.recentSearches())
        core.clearRecentSearches()
        assertTrue(core.recentSearches().isEmpty())

        // New settings persist.
        core.updateSettings(core.settings.copy(audioLanguage = "fa", subtitleLanguage = "en", autoplayNextEpisode = false))
        val reread = TunerCore::class.java.getDeclaredMethod("loadSettings").apply { isAccessible = true }.invoke(core) as app.tuner.core.model.Settings
        assertEquals(Triple("fa", "en", false), Triple(reread.audioLanguage, reread.subtitleLanguage, reread.autoplayNextEpisode))

        // The fixture account expires in 2030.
        assertTrue(core.expiringSources(7).isEmpty())
        assertEquals(listOf(id), core.expiringSources(3650).map { it.id })

        // Next episode follows season order and ends with the last one.
        val show = core.series(core.vodGroups(ContentKind.SERIES, false).first { it.name == "Uncategorized" }, 0, 50).single()
        val episodes = core.seriesDetails(show).episodes
        val next = core.nextEpisode(core.playEpisode(show, episodes.first()))
        assertEquals("Two", next?.title)
        assertNull(core.nextEpisode(next!!))
        assertNull(core.nextEpisode(core.playMovie(core.movies(core.vodGroups(ContentKind.MOVIE, false).first { it.name == "Films" }, 0, 1).single())))
    }

    @Test
    fun changeNotificationsAreScoped() = runBlocking {
        val core = core()
        val id = core.addSource(SourceInput("Demo", SourceType.XTREAM, "demo.test:8080", "u", "p w"))
        assertNull(core.refreshNow(id))
        // addSource's own background sync may start after ours: wait until both are done.
        assertNull(core.refreshNow(id))
        while (core.sourcesSyncing()) Thread.sleep(20)
        val lineup = mutableListOf<Int>()
        val history = mutableListOf<Int>()
        val a = core.watchChanges(Changes.CATALOG or Changes.PREFS) { lineup += it }
        val b = core.watchChanges(Changes.HISTORY) { history += it }
        assertEquals(listOf(Changes.CATALOG or Changes.PREFS), lineup, "initial callback carries every watched kind")
        assertEquals(listOf(Changes.HISTORY), history)

        val ch = core.channels(core.liveGroups(false).first { it.name == "News" }, false).single()
        core.playChannel(ch)
        assertEquals(1, lineup.size, "zapping must not reload lineups")
        assertEquals(listOf(Changes.HISTORY, Changes.HISTORY), history)

        core.setFavorite(ContentKind.LIVE, id, ch.key, true)
        assertEquals(Changes.PREFS, lineup.last())
        core.saveProgress(core.playMovie(core.movies(core.vodGroups(ContentKind.MOVIE, false).first { it.name == "Films" }, 0, 1).single()), 60_000, 600_000)
        assertEquals(2, lineup.size, "progress saves are not lineup changes")
        assertNull(core.refreshNow(id))
        assertEquals(Changes.CATALOG, lineup.last())
        a.cancel(); b.cancel()

        // Bulk favorites: a whole group at once, dividers skipped, already-favorite ones untouched.
        val all = core.channels(core.liveGroups(false).first { it.type == GroupType.ALL }, false)
        val divider = all.first().copy(name = "##### SPORTS #####")
        assertEquals(all.count { !it.favorite }, core.setFavorites(all + divider, true))
        assertEquals(all.size, core.channels(core.liveGroups(false).first { it.type == GroupType.FAVORITES }, false).size)
        assertEquals(0, core.setFavorites(core.channels(core.liveGroups(false).first { it.type == GroupType.ALL }, false), true))
        assertTrue(isDivider("===== UK =====") && !isDivider("UK - Movies HD"))

        // The rewritten guide search returns the programme with an enabled channel for it.
        val hit = core.search("news").programmes.first()
        assertEquals("news.one", hit.programme.channelEpgId)
        assertEquals(ch.key, hit.channel.key)
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

    @Test
    fun xtreamLoginThrottleIsRetried() = runBlocking {
        var logins = 0
        val inner = engine()
        val throttling = MockEngine { req ->
            if (req.url.encodedPath.endsWith("player_api.php") && req.url.parameters["action"] == null && logins++ == 1) {
                respond("", HttpStatusCode.NotFound)
            } else {
                inner.config.requestHandlers.first()(this, req)
            }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TunerDatabase.Schema.create(driver)
        val core = TunerCore(driver, HttpClient(throttling))
        core.verifyXtream("demo.test:8080", "u", "p")
        val id = core.addSource(SourceInput("Demo", SourceType.XTREAM, "demo.test:8080", "u", "p"))
        assertNull(core.refreshNow(id), "second login answered 404 once, then succeeded")
        assertEquals(3, logins)
    }
}
