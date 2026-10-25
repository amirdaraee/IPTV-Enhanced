package app.tuner.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Group
import app.tuner.core.model.GroupType
import app.tuner.core.model.Settings
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core-level stress tests: sync merge, concurrency, backup/restore, search and the newer
 * conveniences (moveFavorite, recent searches, nextEpisode, expiringSources), driven by a
 * MockEngine "provider" whose responses the tests change between syncs.
 */
class StressTest {
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ------------------------------------------------------------------ fake provider

    private class Fake {
        /** Key: host + path, optionally "#action" for player_api.php. */
        val routes = ConcurrentHashMap<String, (HttpRequestData) -> ByteArray?>()
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val delays = ConcurrentHashMap<String, Long>()

        fun engine() = MockEngine { req ->
            val path = req.url.host + req.url.encodedPath
            val action = req.url.parameters["action"]
            val key = if (action != null) "$path#$action" else path
            hits.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            gates[key]?.await()
            delays[key]?.let { delay(it) }
            val body = (routes[key] ?: routes[path])?.invoke(req)
            if (body == null) respond("not found", HttpStatusCode.NotFound)
            else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        }

        fun hit(key: String) = hits[key]?.get() ?: 0
        fun text(key: String, body: () -> String) { routes[key] = { body().encodeToByteArray() } }
    }

    private class Store(@Volatile var saved: String? = null) : UserDataBackup {
        val saves = AtomicInteger()
        override fun save(snapshot: String) { saved = snapshot; saves.incrementAndGet() }
        override fun load() = saved
    }

    private fun memDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TunerDatabase.Schema.create(it) }

    /**
     * In-memory driver whose transactions and statements are serialised across threads, like
     * Android's SQLiteDatabase (one primary connection handed to one thread at a time) and the
     * native driver's single writer. The plain JDBC in-memory driver shares one connection
     * between threads without that, which produces errors no device would.
     */
    private class SerializedDriver(private val d: SqlDriver) : SqlDriver by d {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }

        override fun <R> executeQuery(
            identifier: Int?, sql: String, mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
            parameters: Int, binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
        ) = locked { app.cash.sqldelight.db.QueryResult.Value(d.executeQuery(identifier, sql, mapper, parameters, binders).value) }

        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?) =
            locked { app.cash.sqldelight.db.QueryResult.Value(d.execute(identifier, sql, parameters, binders).value) }

        override fun newTransaction(): app.cash.sqldelight.db.QueryResult<app.cash.sqldelight.Transacter.Transaction> {
            lock.lock() // held until this transaction (or its outermost enclosing one) ends
            val t = try { d.newTransaction().value } catch (e: Throwable) { lock.unlock(); throw e }
            t.afterCommit { lock.unlock() }
            t.afterRollback { lock.unlock() }
            return app.cash.sqldelight.db.QueryResult.Value(t)
        }
    }

    private fun serializedDriver(): SqlDriver = SerializedDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)).also { TunerDatabase.Schema.create(it) }

    private class Env(val core: TunerCore, val db: TunerDatabase, val fake: Fake)

    private fun env(fake: Fake = Fake(), driver: SqlDriver = memDriver(), backup: UserDataBackup? = null) =
        Env(TunerCore(driver, HttpClient(fake.engine()), backup), TunerDatabase(driver), fake)

    /** Adds a source and waits for the background first sync that addSource starts. */
    private suspend fun TunerCore.addAndSync(input: SourceInput): Long {
        val id = addSource(input)
        awaitSynced(id)
        return id
    }

    private suspend fun TunerCore.awaitSynced(id: Long) = withTimeout(120_000) {
        while (true) {
            val s = source(id) ?: break
            if ((s.lastRefreshed != null || s.lastError != null) && !sourcesSyncing()) break
            delay(20)
        }
    }

    private fun m3u(epg: String?, vararg entries: String) = buildString {
        append("#EXTM3U").append(epg?.let { " url-tvg=\"$it\"" } ?: "").append('\n')
        entries.forEach { append(it).append('\n') }
    }

    private fun extinf(name: String, group: String, url: String, tvgId: String? = null) =
        "#EXTINF:-1${tvgId?.let { " tvg-id=\"$it\"" } ?: ""} group-title=\"$group\",$name\n$url"

    private fun guide(ids: List<String>, titles: (String, Int) -> String = { id, i -> "$id show $i" }, from: Int = -3, to: Int = 3) = buildString {
        val base = now - now % hour
        append("<?xml version=\"1.0\"?><tv>")
        ids.forEach { append("<channel id=\"$it\"><display-name>$it</display-name></channel>") }
        ids.forEach { id ->
            for (i in from until to) {
                append("<programme start=\"${fmt.format(base + i * hour)}\" stop=\"${fmt.format(base + (i + 1) * hour)}\" channel=\"$id\">")
                append("<title>${titles(id, i)}</title></programme>")
            }
        }
        append("</tv>")
    }

    private suspend fun TunerCore.group(kind: ContentKind = ContentKind.LIVE, pred: (Group) -> Boolean): Group =
        (if (kind == ContentKind.LIVE) liveGroups(true) else vodGroups(kind, true)).first(pred)

    private suspend fun TunerCore.favorites(): List<Channel> = channels(group { it.type == GroupType.FAVORITES }, true)

    // ------------------------------------------------------------------ Xtream fake

    /** A configurable Xtream panel on host [host]. */
    private class Panel(val fake: Fake, val host: String = "xt.test") {
        @Volatile var live: List<String> = emptyList()
        @Volatile var liveRaw: String? = null
        @Volatile var liveCats = """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"}]"""
        @Volatile var series = """[]"""
        val seriesInfo = ConcurrentHashMap<String, String>()
        val expByUser = ConcurrentHashMap<String, String>()

        init {
            val p = "$host/player_api.php"
            fake.routes[p] = { req ->
                val exp = expByUser[req.url.parameters["username"]] ?: "null"
                """{"user_info":{"auth":1,"status":"Active","exp_date":$exp,"allowed_output_formats":["ts"]},"server_info":{"timezone":"Europe/Berlin"}}""".encodeToByteArray()
            }
            fake.text("$p#get_live_categories") { liveCats }
            fake.text("$p#get_live_streams") { liveRaw ?: live.joinToString(",", "[", "]") }
            fake.text("$p#get_vod_categories") { "[]" }
            fake.text("$p#get_vod_streams") { "[]" }
            fake.text("$p#get_series_categories") { """[{"category_id":"9","category_name":"Shows"}]""" }
            fake.text("$p#get_series") { series }
            fake.routes["$p#get_series_info"] = { req -> seriesInfo[req.url.parameters["series_id"]]?.encodeToByteArray() }
            fake.text("$host/xmltv.php") { "<tv></tv>" }
        }

        fun stream(id: Int, name: String, cat: String?, epg: String? = null) =
            """{"num":$id,"name":"$name","stream_id":"$id","category_id":${cat?.let { "\"$it\"" } ?: "null"},"epg_channel_id":${epg?.let { "\"$it\"" } ?: "null"},"tv_archive":0}"""
    }

    // ================================================================== bugs

    // Regression: two M3U entries with the same group, name and tvg-id (a common "backup stream"
    // pattern) get the same ckey; lastChannel()/previousChannel() and playProgress() then use
    // executeAsOneOrNull() on a query that returns two rows and throw IllegalStateException.
    // TunerCore.kt settingChannel (channelByKey) and playProgress (movieByKey); key from SourceSyncer.m3uKey.
    @Test
    fun duplicateM3uEntriesBreakLastChannelAndResume() = runBlocking {
        val e = env()
        e.fake.text("m.test/dup.m3u") {
            m3u(
                null,
                extinf("CNN", "News", "http://s/cnn.ts", "cnn"),
                extinf("CNN", "News", "http://s/cnn-backup.ts", "cnn"),
                extinf("Film", "Films", "http://s/film.mkv"),
                extinf("Film", "Films", "http://s/film-hd.mkv"),
            )
        }
        val id = e.core.addAndSync(SourceInput("Dup", SourceType.M3U, "http://m.test/dup.m3u"))
        val chans = e.core.channels(e.core.group { it.name == "News" }, false)
        assertEquals(2, chans.size)
        e.core.playChannel(chans[1])
        val last = runCatching { e.core.lastChannel() }

        val films = e.core.movies(e.core.group(ContentKind.MOVIE) { it.name == "Films" }, 0, 10)
        assertEquals(2, films.size)
        e.core.saveProgress(e.core.playMovie(films[0]), 120_000, 600_000)
        val resume = runCatching { e.core.playProgress(e.core.continueWatching(10).single()) }
        assertTrue(last.isSuccess && resume.isSuccess, "lastChannel(): ${last.exceptionOrNull()}; playProgress(): ${resume.exceptionOrNull()}")
        assertTrue(id > 0)
    }

    // Regression: programmes are stored per source but read by epg_key only, so two sources that
    // share a guide (same provider added twice, M3U + Xtream of one account, or a shared
    // public EPG) show every programme twice. Epg.sq programmesInWindow / nextProgrammes.
    @Test
    fun twoSourcesSharingAGuideDuplicateProgrammes() = runBlocking {
        val e = env()
        val playlist = m3u("http://e.test/guide.xml", extinf("One", "News", "http://s/1.ts", "one"))
        e.fake.text("m.test/a.m3u") { playlist }
        e.fake.text("m.test/b.m3u") { playlist }
        e.fake.text("e.test/guide.xml") { guide(listOf("one")) }
        val a = e.core.addAndSync(SourceInput("A", SourceType.M3U, "http://m.test/a.m3u"))
        e.core.addAndSync(SourceInput("B", SourceType.M3U, "http://m.test/b.m3u"))
        val ch = e.core.channels(e.core.group { it.sourceId == a && it.type == GroupType.CATEGORY }, false).single()
        val progs = e.core.guide(listOf(ch), now - hour, now + hour).single().programmes
        assertEquals(progs.distinctBy { it.start }.size, progs.size, "guide row has duplicates: ${progs.map { it.title }}")
        val next = e.core.upcoming(ch, 4)
        assertEquals(next.distinctBy { it.start }.size, next.size, "upcoming has duplicates: ${next.map { it.title }}")
    }

    // Regression: deleting a source while it syncs leaves its catalog and guide behind: the sync's
    // final swap promotes the staged rows (source_id = -id) to the deleted id and the EPG
    // import runs with the stale Source. Rows are orphaned forever (and the guide rows leak
    // into other sources' guides via epg_key). TunerCore.refreshNow / SourceSyncer.sync.
    @Test
    fun deletingASourceMidSyncLeavesNoOrphans() = runBlocking {
        val e = env()
        val gate = CompletableDeferred<Unit>()
        e.fake.gates["m.test/slow.m3u"] = gate
        e.fake.text("m.test/slow.m3u") {
            m3u("http://e.test/g.xml", *(1..50).map { extinf("Chan $it", "G", "http://s/$it.ts", "c$it") }.toTypedArray())
        }
        e.fake.text("e.test/g.xml") { guide((1..50).map { "c$it" }) }
        var lastStates = emptyList<app.tuner.core.model.SyncState>()
        e.core.watchSync { lastStates = it }
        val id = e.core.addSource(SourceInput("Slow", SourceType.M3U, "http://m.test/slow.m3u"))
        withTimeout(10_000) { while (e.fake.hit("m.test/slow.m3u") == 0) delay(10) }
        e.core.deleteSource(id)
        gate.complete(Unit)
        delay(300)
        withTimeout(10_000) { while (e.core.sourcesSyncing()) delay(20) }
        assertTrue(e.core.sources().isEmpty())
        val orphans = e.db.catalogQueries.channelCount(id).executeAsOne() + e.db.catalogQueries.channelCount(-id).executeAsOne()
        val orphanGuide = e.db.epgQueries.programmeCount(id).executeAsOne() + e.db.epgQueries.programmeCount(-id).executeAsOne()
        assertEquals(0L to 0L, orphans to orphanGuide, "channels / programmes left for the deleted source")
        assertTrue(lastStates.none { it.sourceId == id }, "sync state of a deleted source is still reported: $lastStates")
    }

    // Regression: an Xtream sync whose live list comes back empty (a panel hiccup answering 200 + "[]")
    // or truncated mid-body is accepted as the new lineup and replaces the full one with no
    // error. M3U refuses an empty playlist ("The playlist has no entries"), Xtream does not.
    // SourceSyncer.syncXtream; XtreamClient.streamObjects does not check the JSON closed.
    @Test
    fun xtreamEmptyOrTruncatedLineupDoesNotWipeChannels() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = (1..3).map { panel.stream(it, "Ch $it", "1") }
        val id = e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        assertEquals(3L, e.db.catalogQueries.channelCount(id).executeAsOne())

        panel.liveRaw = "[]"
        val emptyResult = e.core.refreshNow(id)
        val afterEmpty = e.db.catalogQueries.channelCount(id).executeAsOne()

        panel.liveRaw = panel.live.joinToString(",", "[", "]").dropLast(30)
        val truncResult = e.core.refreshNow(id)
        val afterTrunc = e.db.catalogQueries.channelCount(id).executeAsOne()
        assertTrue(
            (emptyResult != null || afterEmpty == 3L) && (truncResult != null || afterTrunc == 3L),
            "empty list -> error=$emptyResult channels=$afterEmpty; truncated -> error=$truncResult channels=$afterTrunc",
        )
    }

    // Regression: restore maps source ids for prefs/progress/recents but not for the last_channel /
    // previous_channel settings ("<sourceId>|<key>"), so after a restore (ids restart at 1)
    // "autoplay last channel" and the previous-channel toggle point at nothing (or at another
    // source's channel). UserData.restore, settings loop.
    @Test
    fun restoreRemapsLastAndPreviousChannel() = runBlocking {
        val store = Store()
        val a = env(backup = store)
        a.fake.text("m.test/p.m3u") { m3u(null, extinf("One", "G", "http://s/1.ts"), extinf("Two", "G", "http://s/2.ts")) }
        val first = a.core.addAndSync(SourceInput("Old1", SourceType.M3U, "http://m.test/p.m3u"))
        val second = a.core.addAndSync(SourceInput("Old2", SourceType.M3U, "http://m.test/p.m3u"))
        val keep = a.core.addAndSync(SourceInput("Keep", SourceType.M3U, "http://m.test/p.m3u"))
        a.core.deleteSource(first); a.core.deleteSource(second)
        val chans = a.core.channels(a.core.group { it.sourceId == keep && it.type == GroupType.CATEGORY }, false)
        a.core.playChannel(chans[0]); a.core.playChannel(chans[1])
        store.saved = a.core.exportUserData()
        a.core.close()

        val b = env(fake = a.fake, backup = Store(store.saved))
        assertEquals(1, b.core.restoredSources)
        val id = b.core.sources().single().id
        assertTrue(id != keep, "ids differ after restore ($id vs $keep), which is the point of the test")
        assertNull(b.core.refreshNow(id))
        b.core.awaitSynced(id)
        assertEquals("Two", b.core.lastChannel()?.name, "last channel after restore")
        assertEquals("One", b.core.previousChannel()?.name, "previous channel after restore")
    }

    // Regression: restore does not validate enum columns; a snapshot with a source type or progress
    // kind this version doesn't know (a newer app's backup, or a hand-edited one) is written
    // to the DB, after which sources()/hasSources()/refreshStale() and continueWatching()
    // throw on every launch (SourceType.valueOf / ContentKind.valueOf in repo/Mappers.kt).
    @Test
    fun restoreOfUnknownEnumValuesDoesNotBrickTheApp() = runBlocking {
        val a = env()
        a.fake.text("m.test/p.m3u") { m3u(null, extinf("One", "G", "http://s/1.ts")) }
        a.core.addAndSync(SourceInput("Good", SourceType.M3U, "http://m.test/p.m3u"))
        a.core.addAndSync(SourceInput("Future", SourceType.M3U, "http://m.test/p.m3u"))
        val snapshot = a.core.exportUserData()
            .replaceFirst("\"type\":\"M3U\"", "\"type\":\"STALKER\"")
            .replace("\"progress\":[]", "\"progress\":[{\"key\":\"podcast:1:x\",\"sourceId\":1,\"kind\":\"PODCAST\",\"title\":\"t\",\"subtitle\":null,\"image\":null,\"url\":\"u\",\"positionMs\":60000,\"durationMs\":0,\"updatedAt\":1,\"parentKey\":null}]")
        val b = env(fake = a.fake, backup = Store(snapshot))
        // The playlist of the unknown type is skipped (this version can't use it); the other restores.
        assertEquals(1, b.core.restoredSources)
        val sources = runCatching { b.core.sources() }
        assertTrue(sources.isSuccess, "sources() threw after restoring an unknown type: ${sources.exceptionOrNull()}")
        val cw = runCatching { b.core.continueWatching(10) }
        assertTrue(cw.isSuccess, "continueWatching() threw after restoring an unknown kind: ${cw.exceptionOrNull()}")
    }

    // Regression (minor): setFavorites stamps favorite_at = now + i, so after favoriting a big group
    // a channel favorited on its own within the next N ms sorts *inside* that group instead of
    // at the end ("a channel favorited afterwards goes to the end"). TunerCore.setFavorites/setFavorite.
    @Test
    fun singleFavoriteAfterBulkGoesToTheEnd() = runBlocking {
        val e = env()
        e.fake.text("m.test/big.m3u") { m3u(null, *(1..3001).map { extinf("C$it", "G", "http://s/$it.ts") }.toTypedArray()) }
        val id = e.core.addAndSync(SourceInput("Big", SourceType.M3U, "http://m.test/big.m3u"))
        val all = e.core.channels(e.core.group { it.type == GroupType.CATEGORY }, false)
        e.core.setFavorites(all.dropLast(1), true)
        e.core.setFavorite(ContentKind.LIVE, id, all.last().key, true)
        assertEquals("C3001", e.core.favorites().last().name)
    }

    // Regression: a hidden channel stayed in Favorites and Recents (only category groups filtered it).
    @Test
    fun hiddenChannelsLeaveFavoritesAndRecents() = runBlocking {
        val e = env()
        e.fake.text("m.test/h.m3u") { m3u(null, extinf("A", "G", "http://s/a.ts"), extinf("B", "G", "http://s/b.ts")) }
        val id = e.core.addAndSync(SourceInput("H", SourceType.M3U, "http://m.test/h.m3u"))
        val (a, b) = e.core.channels(e.core.group { it.type == GroupType.CATEGORY }, false)
        e.core.setFavorites(listOf(a, b), true)
        e.core.playChannel(a)
        e.core.setHidden(ContentKind.LIVE, id, a.key, true)
        assertEquals(listOf("B"), e.core.channels(e.core.group { it.type == GroupType.FAVORITES }, false).map { it.name })
        assertTrue(e.core.channels(e.core.group { it.type == GroupType.RECENTS }, false).none { it.id == a.id })
    }

    // Upgrade: M3U keys were "group|name|tvg-id"; data saved under them must follow the new keys.
    @Test
    fun legacyM3uKeysAreMigrated() = runBlocking {
        val e = env()
        e.fake.text("m.test/l.m3u") { m3u(null, extinf("A", "G", "http://s/a.ts", "a.id"), extinf("B", "G", "http://s/b.ts")) }
        val id = e.core.addAndSync(SourceInput("L", SourceType.M3U, "http://m.test/l.m3u"))
        val u = e.db.userQueries
        e.db.catalogQueries.restoreItemPref(id, "LIVE", "G|A|a.id", 1, 1, 0, 0)
        e.db.catalogQueries.restoreItemPref(id, "LIVE", "G|B|", 0, 0, 1, 0)
        u.touchRecent(id, "G|A|a.id", 1)
        u.putSetting(TunerCore.LAST_CHANNEL, "$id|G|A|a.id")
        assertNull(e.core.refreshNow(id))
        assertEquals(listOf("A"), e.core.favorites().map { it.name })
        assertEquals(listOf("B"), e.core.hiddenChannels().map { it.name })
        assertEquals("A", e.core.lastChannel()?.name)
        assertTrue(e.core.channels(e.core.group { it.type == GroupType.RECENTS }, false).any { it.name == "A" })
    }

    // Two playlists with the same name: their groups (and other items) must still be told apart.
    @Test
    fun sameNamedSourcesGetDistinctLabels() = runBlocking {
        val e = env()
        e.fake.text("m.test/a.m3u") { m3u(null, extinf("A", "News", "http://s/a.ts")) }
        e.fake.text("m.test/b.m3u") { m3u(null, extinf("B", "News", "http://s/b.ts")) }
        val a = e.core.addAndSync(SourceInput("Home", SourceType.M3U, "http://m.test/a.m3u"))
        val b = e.core.addAndSync(SourceInput("Home", SourceType.M3U, "http://m.test/b.m3u"))
        val labels = e.core.sourceLabels()
        assertEquals("Home (a.m3u)", labels[a])
        assertEquals("Home (b.m3u)", labels[b])
        assertEquals(setOf("Home (a.m3u)", "Home (b.m3u)"), e.core.liveGroups(false).mapNotNull { it.sourceName }.toSet())
    }

    @Test
    fun appleNetworkErrorsAreShortened() {
        val dump = "Exception in http request: Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect to the server.\" " +
            "UserInfo={NSErrorFailingURLStringKey=http://h/player_api.php?username=u&password=p}"
        assertEquals("Could not connect to the server.", app.tuner.core.sync.describe(RuntimeException(dump)))
    }

    // Regression: a gzip body that ends before the deflate stream is complete (dropped connection,
    // proxy cut-off) is not detected: TextPipeline.finish() never checks inflater.finished(),
    // so a truncated .m3u.gz / .xml.gz silently replaces the full lineup or guide with whatever
    // decompressed so far. io/Inflater.kt TextPipeline.finish, jvmAndroidMain Inflater.jvm.kt.
    @Test
    fun truncatedGzipDoesNotReplaceTheLineup() = runBlocking {
        val e = env()
        val full = m3u(null, *(1..5000).map { extinf("Channel number $it", "G${it % 10}", "http://s/$it.ts", "id$it") }.toTypedArray())
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(full.encodeToByteArray()) } }.toByteArray()
        var body = gz
        e.fake.routes["m.test/p.m3u.gz"] = { body }
        val id = e.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u.gz"))
        assertEquals(5000L, e.db.catalogQueries.channelCount(id).executeAsOne())
        body = gz.copyOf(gz.size / 2)
        val err = e.core.refreshNow(id)
        val count = e.db.catalogQueries.channelCount(id).executeAsOne()
        assertTrue(err != null || count == 5000L, "truncated gzip accepted: error=$err, channels=$count")
    }

    /** Xtream panels send every type for every field; none of this may fail the sync. */
    @Test
    fun xtreamOddJsonTypes() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.liveCats = """{"a":{"category_id":1,"category_name":null},"b":{"category_id":"2","category_name":"Two"},"c":{"category_name":"no id"},"d":[]}"""
        panel.liveRaw = """{"x":{"stream_id":10,"num":"1","name":null,"category_id":1,"tv_archive":"1","tv_archive_duration":"abc","epg_channel_id":123},
            "y":{"stream_id":"11","num":1.5,"name":"Fl\u00f6t \"q\" {x}","category_id":[],"tv_archive":true,"tv_archive_duration":3.0,"stream_icon":[]},
            "z":{"stream_id":null,"name":"no id"},"w":"string","v":[1,2,3],
            "u":{"stream_id":"12","name":"Uncat","category_id":"999","added":"not a number","rating":"7,5"}}"""
        val id = e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        assertNull(e.core.source(id)!!.lastError)
        val groups = e.core.liveGroups(true).filter { it.type == GroupType.CATEGORY }.map { it.name }
        assertEquals(listOf("1", "Two", "Uncategorized"), groups)
        val all = e.core.channels(e.core.group { it.type == GroupType.ALL }, true).associateBy { it.key }
        assertEquals(setOf("10", "11", "12"), all.keys)
        assertEquals("10", all.getValue("10").name)
        assertEquals("Flöt \"q\" {x}", all.getValue("11").name)
        assertEquals("123", all.getValue("10").epgId)
        assertEquals(3, all.getValue("11").catchupDays)
    }

    // Regression: a stream whose category_id is not in get_live_categories (deleted/hidden category on
    // the panel, very common) keeps category_key = that id. cats() adds an "Uncategorized"
    // group for it, but channelsInGroup(…, "Uncategorized") only finds null-category streams, so
    // the group is empty and the stream is reachable only via All channels / search.
    // SourceSyncer.syncXtream: category_key = s.categoryId ?: UNCATEGORIZED vs cats().
    @Test
    fun streamsInUnknownCategoryAppearInUncategorized() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = listOf(panel.stream(1, "Known", "1"), panel.stream(2, "Orphan", "999"))
        e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        val uncategorized = e.core.group { it.name == "Uncategorized" }
        assertEquals(listOf("Orphan"), e.core.channels(uncategorized, true).map { it.name })
    }

    // ================================================================== new features

    @Test
    fun moveFavoriteEdgeCases() = runBlocking {
        val e = env()
        e.fake.text("m.test/a.m3u") { m3u(null, extinf("A1", "G", "http://a/1.ts"), extinf("A2", "G", "http://a/2.ts")) }
        e.fake.text("m.test/b.m3u") { m3u(null, extinf("B1", "H", "http://b/1.ts")) }
        val a = e.core.addAndSync(SourceInput("A", SourceType.M3U, "http://m.test/a.m3u"))
        val b = e.core.addAndSync(SourceInput("B", SourceType.M3U, "http://m.test/b.m3u"))
        val ga = e.core.channels(e.core.group { it.sourceId == a && it.type == GroupType.CATEGORY }, false)
        val gb = e.core.channels(e.core.group { it.sourceId == b && it.type == GroupType.CATEGORY }, false)
        for (ch in listOf(ga[0], gb[0], ga[1])) { e.core.setFavorite(ContentKind.LIVE, ch.sourceId, ch.key, true); delay(2) }
        assertEquals(listOf("A1", "B1", "A2"), e.core.favorites().map { it.name })
        e.core.moveFavorite(ga[1], -100)
        assertEquals(listOf("A2", "A1", "B1"), e.core.favorites().map { it.name })
        e.core.moveFavorite(ga[1], +100)
        assertEquals(listOf("A1", "B1", "A2"), e.core.favorites().map { it.name })
        // a channel that is not a favorite: no-op
        e.core.moveFavorite(ga[0].copy(id = -5), 1)

        // Disable B, reorder, re-enable: B's favorite must still be there.
        val src = e.core.source(b)!!
        fun input(enabled: Boolean) = SourceInput(src.name, src.type, src.url, enabled = enabled)
        e.core.updateSource(b, input(false))
        e.core.moveFavorite(ga[1], -1)
        assertEquals(listOf("A2", "A1"), e.core.favorites().map { it.name })
        e.core.updateSource(b, input(true))
        val order = e.core.favorites().map { it.name }
        assertEquals(setOf("A1", "A2", "B1"), order.toSet())
        println("favorites after reorder with B disabled, then re-enabled: $order (B1 was 2nd)")

        // Favorited later goes to the end, even after a reorder (which uses small numbers).
        e.core.setFavorite(ContentKind.LIVE, a, ga[0].key, false)
        e.core.setFavorite(ContentKind.LIVE, a, ga[0].key, true)
        assertEquals("A1", e.core.favorites().last().name)
    }

    @Test
    fun recentSearchesEdgeCases() = runBlocking {
        val e = env()
        val long = "x".repeat(100_000)
        listOf("one", "two\nlines", "  three  ", "\r\n", "a", "Ünïcödé", "ünïcödé", "İstanbul", "😀😀", long, "ǅemal", "DŽEMAL", "nine", "ten").forEach(e.core::rememberSearch)
        val r = e.core.recentSearches()
        assertEquals(8, r.size, r.toString())
        assertEquals("ten", r[0])
        assertTrue(r.none { '\n' in it })
        assertEquals(1, r.count { it.equals("ünïcödé", ignoreCase = true) }, "case-folded duplicates collapse")
        assertTrue(long in r, "very long query stored")
        e.core.rememberSearch("TWO LINES")
        assertEquals(listOf("TWO LINES"), e.core.recentSearches().filter { it.equals("two lines", true) })

    }

    @Test
    fun expiringSourcesEdgeCases() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = listOf(panel.stream(1, "One", "1"))
        val sec = now / 1000
        panel.expByUser["none"] = "null"
        panel.expByUser["empty"] = "\"\""
        panel.expByUser["past"] = "\"${sec - 86_400}\""
        panel.expByUser["soon"] = "${sec + 2 * 86_400}"
        panel.expByUser["far"] = "\"99999999999\""
        panel.expByUser["float"] = "\"${sec + 86_400}.0\""
        panel.expByUser["off"] = "\"${sec + 86_400}\""
        val ids = panel.expByUser.keys.associateWith { u -> e.core.addAndSync(SourceInput(u, SourceType.XTREAM, "xt.test", u, "p")) }
        val off = e.core.source(ids.getValue("off"))!!
        e.core.updateSource(off.id, SourceInput(off.name, off.type, off.url, off.username, off.password, enabled = false))
        assertEquals(setOf("past", "soon", "float"), e.core.expiringSources(7).map { it.name }.toSet())
        assertEquals(setOf("past"), e.core.expiringSources(0).map { it.name }.toSet())
        assertEquals(setOf("past", "soon", "float", "far"), e.core.expiringSources(Int.MAX_VALUE).map { it.name }.toSet())
        assertEquals(setOf("past"), e.core.expiringSources(-1).map { it.name }.toSet())
    }

    @Test
    fun nextEpisodeEdgeCases() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = listOf(panel.stream(1, "One", "1"))
        panel.series = """[{"series_id":"s1","name":"Shuffled","category_id":"9"},{"series_id":"s2","name":"NoSeasons","category_id":"9"},
            {"series_id":"s3","name":"DupNumbers","category_id":"9"},{"series_id":"s4","name":"Broken","category_id":"9"}]"""
        panel.seriesInfo["s1"] = """{"episodes":{"2":[{"id":"21","episode_num":1,"season":2,"title":"S2E1"}],"1":[{"id":"12","episode_num":"2","season":"1","title":"S1E2"},{"id":"11","episode_num":1,"info":{"season":"1"},"title":"S1E1"}]}}"""
        panel.seriesInfo["s2"] = """{"episodes":[[{"id":"a","episode_num":"1","title":"A"},{"id":"b","episode_num":"2","title":"B"}]]}"""
        panel.seriesInfo["s3"] = """{"episodes":{"1":[{"id":"x","episode_num":1,"title":"X"},{"id":"y","episode_num":1,"title":"Y"},{"id":"z","episode_num":2,"title":"Z"}]}}"""
        panel.seriesInfo["s4"] = """<html>500</html>"""
        val id = e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        val shows = e.core.series(e.core.group(ContentKind.SERIES) { it.name == "Shows" }, 0, 10).associateBy { it.name }

        suspend fun chain(name: String): List<String> {
            val s = shows.getValue(name)
            val eps = e.core.seriesDetails(s).episodes
            var req: app.tuner.core.model.PlaybackRequest? = e.core.playEpisode(s, eps.first())
            val out = ArrayList<String>()
            while (req != null && out.size < 10) { out += req.title; req = e.core.nextEpisode(req) }
            return out
        }
        assertEquals(listOf("S1E1", "S1E2", "S2E1"), chain("Shuffled"))
        assertEquals(listOf("A", "B"), chain("NoSeasons"))
        assertEquals(listOf("X", "Y", "Z"), chain("DupNumbers"))

        // An episode request whose series disappeared from the provider: null, not a crash.
        val s1 = shows.getValue("Shuffled")
        val req = e.core.playEpisode(s1, e.core.seriesDetails(s1).episodes.first())
        panel.series = """[{"series_id":"s2","name":"NoSeasons","category_id":"9"}]"""
        assertNull(e.core.refreshNow(id))
        assertNull(e.core.nextEpisode(req))
        // A malformed parent key or a live request.
        assertNull(e.core.nextEpisode(req.copy(progressParentKey = "series:abc")))
        assertNull(e.core.nextEpisode(req.copy(progressParentKey = null)))
        assertNull(e.core.nextEpisode(req.copy(kind = ContentKind.LIVE)))
    }

    // Regression: when episode objects carry no "season" field (common: the season is only the key of
    // the "episodes" object, {"1":[...],"2":[...]}), every episode falls back to season 1, so
    // seasons collapse into one list and nextEpisode plays S2E1 before S1E1.
    // XtreamClient.seriesInfo ignores the map key (`e.values.flatMap`, `?: 1`).
    @Test
    fun seasonComesFromEpisodesMapKey() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = listOf(panel.stream(1, "One", "1"))
        panel.series = """[{"series_id":"s1","name":"Keyed","category_id":"9"}]"""
        panel.seriesInfo["s1"] = """{"episodes":{"1":[{"id":"11","episode_num":1,"title":"S1E1"},{"id":"12","episode_num":2,"title":"S1E2"}],"2":[{"id":"21","episode_num":1,"title":"S2E1"}]}}"""
        e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        val show = e.core.series(e.core.group(ContentKind.SERIES) { it.name == "Shows" }, 0, 10).single()
        val d = e.core.seriesDetails(show)
        assertEquals(listOf(1, 2), d.seasons)
        assertEquals(listOf("S1E1", "S1E2", "S2E1"), d.episodes.map { it.title })
    }

    @Test
    fun settingsSurviveBackupIncludingOldBackups() = runBlocking {
        val a = env()
        a.fake.text("m.test/p.m3u") { m3u(null, extinf("One", "G", "http://s/1.ts")) }
        a.core.addAndSync(SourceInput("P \"quoted\" ✓ \\ name", SourceType.M3U, "http://m.test/p.m3u", userAgent = "UA/1 \"x\""))
        val custom = Settings(5, 3, false, 9, true, true, "fa", "en", false)
        a.core.updateSettings(custom)
        a.core.rememberSearch("شبکه")
        a.core.setPin("1234")
        repeat(5) { a.core.unlock("0000") }
        val snap = a.core.exportUserData()
        assertTrue("pin_failures" !in snap && "pin_locked_until" !in snap, "lockout is not exported")

        val b = env(fake = a.fake, backup = Store(snap))
        assertEquals(1, b.core.restoredSources)
        assertEquals(custom, b.core.settings)
        assertEquals(listOf("شبکه"), b.core.recentSearches())
        assertTrue(b.core.hasPin() && b.core.unlock("1234"))
        assertEquals("P \"quoted\" ✓ \\ name", b.core.sources().single().name)

        // A backup written before the language/autoplay settings existed, by a "newer" version with extra fields.
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(snap).jsonObject
        val oldSettings = parsed.getValue("settings").jsonObject.filterKeys { it !in setOf("audio_lang", "subtitle_lang", "autoplay_next") }
        val old = kotlinx.serialization.json.JsonObject(
            parsed + mapOf(
                "version" to kotlinx.serialization.json.JsonPrimitive(7),
                "futureField" to kotlinx.serialization.json.Json.parseToJsonElement("{\"x\":[1,2]}"),
                "settings" to kotlinx.serialization.json.JsonObject(oldSettings),
            ),
        ).toString()
        val c = env(fake = a.fake, backup = Store(old))
        assertEquals(1, c.core.restoredSources)
        assertEquals(custom.copy(audioLanguage = "", subtitleLanguage = "", autoplayNextEpisode = true), c.core.settings)

        // Corrupt / partial snapshots restore nothing and do not throw.
        for (bad in listOf("", "{", "null", "[]", snap.take(snap.length / 2), "{\"sources\":[{\"id\":1}]}")) {
            assertEquals(0, env(fake = a.fake, backup = Store(bad)).core.restoredSources, bad.take(40))
        }
    }

    @Test
    fun backupRoundTripWithManyItems() = runBlocking {
        val a = env()
        a.fake.text("m.test/p.m3u") { m3u(null, *(1..2000).map { extinf("Ch $it ' \" | : 📺", "G${it % 7}", "http://s/$it.ts", "id$it") }.toTypedArray()) }
        val id = a.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u"))
        val all = a.core.channels(a.core.group { it.type == GroupType.ALL }, true)
        assertEquals(2000, all.size)
        a.core.setFavorites(all.filterIndexed { i, _ -> i % 3 == 0 }, true)
        all.filterIndexed { i, _ -> i % 5 == 0 }.forEach { a.core.setHidden(ContentKind.LIVE, id, it.key, true) }
        a.core.moveGroup(a.core.group { it.name == "G3" }, -10)
        val snap = a.core.exportUserData()

        val b = env(fake = a.fake, backup = Store(snap))
        val nid = b.core.sources().single().id
        assertNull(b.core.refreshNow(nid))
        b.core.awaitSynced(nid)
        assertEquals(a.core.favorites().map { it.name }, b.core.favorites().map { it.name })
        assertEquals(a.core.hiddenChannels().map { it.name }, b.core.hiddenChannels().map { it.name })
        assertEquals(a.core.liveGroups(true).map { it.name }, b.core.liveGroups(true).map { it.name })
        assertEquals("G3", b.core.liveGroups(true).first { it.type == GroupType.CATEGORY }.name)
    }

    // ================================================================== sync merge

    @Test
    fun xtreamResyncKeepsPrefsAcrossRenamesMovesAndReorders() = runBlocking {
        val e = env()
        val panel = Panel(e.fake)
        panel.live = listOf(panel.stream(1, "One", "1"), panel.stream(2, "Two", "1"), panel.stream(3, "Three", "2"))
        val id = e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        val news = e.core.group { it.name == "News" }
        val sports = e.core.group { it.name == "Sports" }
        val (one, two) = e.core.channels(news, true)
        e.core.setFavorite(ContentKind.LIVE, id, one.key, true)
        e.core.setHidden(ContentKind.LIVE, id, two.key, true)
        e.core.setLocked(ContentKind.LIVE, id, one.key, true)
        e.core.setGroupHidden(sports, true)
        e.core.moveGroup(sports, -1)

        // Provider renames a category and a channel, moves One to Sports, reorders everything, drops Three.
        panel.liveCats = """[{"category_id":"2","category_name":"Sport (renamed)"},{"category_id":"1","category_name":"News"},{"category_id":"3","category_name":"New"}]"""
        panel.live = listOf(panel.stream(2, "Two", "1"), panel.stream(1, "One (renamed)", "2"), panel.stream(4, "Four", "3"))
        assertNull(e.core.refreshNow(id))
        val fav = e.core.favorites().single()
        assertEquals("One (renamed)", fav.name)
        assertTrue(fav.locked)
        assertEquals("2", fav.categoryKey)
        assertEquals(listOf("Two"), e.core.hiddenChannels().map { it.name })
        val groups = e.core.liveGroups(true).filter { it.type == GroupType.CATEGORY }
        assertEquals(listOf("Sport (renamed)", "News", "New"), groups.map { it.name })
        assertTrue(groups.first().hidden)
        assertEquals(3L, e.db.catalogQueries.channelCount(id).executeAsOne(), "no duplicate rows")
        // Three comes back later with its prefs intact (prefs are keyed, never pruned).
        e.core.setFavorite(ContentKind.LIVE, id, "4", true)
        panel.live = listOf(panel.stream(4, "Four", "3"))
        assertNull(e.core.refreshNow(id))
        panel.live = listOf(panel.stream(4, "Four", "3"), panel.stream(1, "One", "1"))
        assertNull(e.core.refreshNow(id))
        assertEquals(setOf("One", "Four"), e.core.favorites().map { it.name }.toSet())
    }

    // Regression (design): M3U channel keys are "group|name|tvg-id", so a provider renaming a
    // channel or moving it to another group silently drops its favorite/hidden/locked state
    // (even when tvg-id is stable). SourceSyncer.m3uKey.
    @Test
    fun m3uResyncKeepsFavoriteWhenChannelMovesGroup() = runBlocking {
        val e = env()
        var body = m3u(null, extinf("CNN", "News", "http://s/cnn.ts", "cnn.us"), extinf("BBC", "News", "http://s/bbc.ts", "bbc.uk"))
        e.fake.text("m.test/p.m3u") { body }
        val id = e.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u"))
        val cnn = e.core.channels(e.core.group { it.name == "News" }, false).first { it.name == "CNN" }
        e.core.setFavorite(ContentKind.LIVE, id, cnn.key, true)
        body = m3u(null, extinf("CNN", "US News", "http://s/cnn.ts", "cnn.us"), extinf("BBC", "News", "http://s/bbc.ts", "bbc.uk"))
        assertNull(e.core.refreshNow(id))
        assertEquals(listOf("CNN"), e.core.favorites().map { it.name }, "favorite lost after the provider moved the channel")
    }

    // ================================================================== concurrency

    // Regression (race, was intermittent): refreshNow's "already running?" check (lock.isLocked) is separate
    // from taking the lock, so callers that check before the first one has locked each queue up
    // and run their own full sync afterwards (seen: 6 downloads for 8 concurrent calls).
    // TunerCore.refreshNow, now tryLock. The race showed up in roughly 1 of 300 rounds, which is
    // why this runs many rounds.
    @Test
    fun concurrentRefreshNowSyncsOnce() = runBlocking {
        val e = env()
        e.fake.text("m.test/p.m3u") { m3u(null, *(1..200).map { extinf("C$it", "G", "http://s/$it.ts") }.toTypedArray()) }
        val id = e.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u"))
        e.fake.delays["m.test/p.m3u"] = 30
        val perRound = ArrayList<Int>()
        repeat(200) {
            val before = e.fake.hit("m.test/p.m3u")
            val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { e.core.refreshNow(id) } }.awaitAll() }
            assertTrue(results.all { it == null }, results.toString())
            perRound += e.fake.hit("m.test/p.m3u") - before
            assertEquals(200L, e.db.catalogQueries.channelCount(id).executeAsOne())
            assertEquals(0L, e.db.catalogQueries.channelCount(-id).executeAsOne(), "staging left behind")
        }
        assertTrue(perRound.all { it == 1 }, "downloads per round of 8 concurrent refreshNow calls: ${perRound.withIndex().filter { it.value != 1 }}")
    }

    /** Many writers while syncs and the backup autosave run. */
    @Test
    fun concurrentMutationsDuringSyncStayConsistent() = runBlocking {
        val store = Store()
        val e = env(driver = serializedDriver(), backup = store)
        val panel = Panel(e.fake)
        panel.liveCats = (1..20).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"Cat $it"}""" }
        panel.live = (1..3000).map { panel.stream(it, "Chan $it", "${it % 20 + 1}") }
        panel.series = """[{"series_id":"s1","name":"Show","category_id":"9"}]"""
        val id = e.core.addAndSync(SourceInput("X", SourceType.XTREAM, "xt.test", "u", "p"))
        val callbacks = AtomicInteger()
        val sub = e.core.watchChanges(Changes.ALL) { callbacks.incrementAndGet() }
        val errors = CopyOnWriteArrayList<Throwable>()
        val keys = (1..3000).map { it.toString() }
        val groups = e.core.liveGroups(true).filter { it.type == GroupType.CATEGORY }
        val movieReq = app.tuner.core.model.PlaybackRequest(
            "u", "t", null, null, null, null, false, 0, "movie:$id:m", null, id, ContentKind.MOVIE, null,
        )
        coroutineScope {
            launch(Dispatchers.Default) {
                repeat(4) { runCatching { assertNull(e.core.refreshNow(id)) }.onFailure { errors += it } }
            }
            repeat(8) { w ->
                launch(Dispatchers.Default) {
                    val r = Random(w)
                    repeat(150) {
                        runCatching {
                            val k = keys.random(r)
                            when (r.nextInt(10)) {
                                0 -> e.core.setFavorite(ContentKind.LIVE, id, k, r.nextBoolean())
                                1 -> e.core.setHidden(ContentKind.LIVE, id, k, r.nextBoolean())
                                2 -> e.core.setLocked(ContentKind.LIVE, id, k, r.nextBoolean())
                                3 -> e.core.favorites().randomOrNull(r)?.let { e.core.moveFavorite(it, r.nextInt(-3, 4)) }
                                4 -> e.core.moveGroup(groups.random(r), r.nextInt(-2, 3))
                                5 -> e.core.saveProgress(movieReq.copy(progressKey = "movie:$id:${r.nextInt(50)}"), r.nextLong(5_000, 99_000), 100_000)
                                6 -> e.core.rememberSearch("q ${r.nextInt(20)}")
                                7 -> e.core.search("chan ${r.nextInt(300)}")
                                8 -> e.core.channel(r.nextLong(1, 20_000))?.let { e.core.playChannel(it) }
                                else -> e.core.channels(e.core.group { it.type == GroupType.ALL }, false)
                            }
                        }.onFailure { errors += it }
                    }
                }
            }
        }
        assertTrue(errors.isEmpty(), "errors: ${errors.take(5).joinToString { it.toString() }}")
        assertTrue(callbacks.get() > 10)
        sub.cancel()
        val favs = e.core.favorites()
        assertEquals(favs.size, favs.map { it.id }.distinct().size, "duplicate favorites")
        assertEquals(3000L, e.db.catalogQueries.channelCount(id).executeAsOne())
        val positions = e.db.catalogQueries.allGroupPrefs().executeAsList().mapNotNull { it.custom_position }
        assertEquals(positions.size, positions.distinct().size, "group positions collide: ${positions.sorted()}")
        // The autosave catches up with the final state.
        val deadline = System.currentTimeMillis() + 8_000
        while (store.saved != e.core.exportUserData() && System.currentTimeMillis() < deadline) delay(100)
        assertEquals(e.core.exportUserData(), store.saved, "backup is current")
        e.core.close()
    }

    // ================================================================== search

    // Regression: the guide search passes the raw query into LIKE '%' || q || '%' without escaping, so
    // '_' and '%' act as wildcards ("a_c" finds "abc"), and unlike channel search it is not
    // accent- or (non-ASCII) case-insensitive ("cafe" misses "Café", "école" misses "ÉCOLE").
    // Epg.sq searchProgrammes; TunerCore.search passes query.trim() instead of the normalised text.
    @Test
    fun guideSearchTreatsWildcardsLiterallyAndFoldsAccents() = runBlocking {
        val e = env()
        val titles = mapOf("t1" to "abc show", "t2" to "a_c show", "t3" to "Café Society", "t4" to "ÉCOLE des fans")
        e.fake.text("m.test/p.m3u") {
            m3u("http://e.test/g.xml", *titles.keys.map { extinf("Café TV $it", "G", "http://s/$it.ts", it) }.toTypedArray())
        }
        e.fake.text("e.test/g.xml") { guide(titles.keys.toList(), { id, _ -> titles.getValue(id) }, from = 0, to = 1) }
        e.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u"))
        assertEquals(4, e.core.search("cafe tv").channels.size, "channel search folds accents")
        // Nothing here may throw.
        listOf("O'Brien", "\"; DROP TABLE channel; --", "\\", "%%%", "__", "x".repeat(10_000), "شبکه", "\u0000ab").forEach { e.core.search(it) }

        val wildcard = e.core.search("a_c").programmes.map { it.programme.title }
        val accent = e.core.search("cafe").programmes.map { it.programme.title }
        val upper = e.core.search("école").programmes.map { it.programme.title }
        assertEquals(Triple(listOf("a_c show"), listOf("Café Society"), listOf("ÉCOLE des fans")), Triple(wildcard, accent, upper))
    }

    // Regression (low): a query of 50k+ characters makes SQLite reject the LIKE pattern
    // ("LIKE or GLOB pattern too complex", SQLITE_MAX_LIKE_PATTERN_LENGTH) and search() throws
    // instead of returning no results. TunerCore.search does not bound the query length.
    @Test
    fun veryLongSearchQueryDoesNotThrow() = runBlocking {
        val e = env()
        e.fake.text("m.test/p.m3u") { m3u("http://e.test/g.xml", extinf("One", "G", "http://s/1.ts", "one")) }
        e.fake.text("e.test/g.xml") { guide(listOf("one")) }
        e.core.addAndSync(SourceInput("P", SourceType.M3U, "http://m.test/p.m3u"))
        val r = runCatching { e.core.search("x".repeat(50_000)) }
        assertTrue(r.isSuccess, "search threw: ${r.exceptionOrNull()}")
    }

    // ================================================================== scale

    /** 200k channels and 1M programmes: completes, stays correct, and memory stays bounded. */
    @Test
    fun largeImportAndGuide() = runBlocking {
        val e = env()
        val channels = 200_000
        val epgChannels = 10_000
        val perChannel = 100
        // Generated straight into gzip so the test itself doesn't need hundreds of MB.
        fun gz(write: (java.io.Writer) -> Unit): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.io.OutputStreamWriter(java.util.zip.GZIPOutputStream(out), Charsets.UTF_8).buffered().use(write)
            return out.toByteArray()
        }
        val playlist = gz { w ->
            w.write("#EXTM3U url-tvg=\"http://e.test/big.xml\"\n")
            for (i in 0 until channels) w.write("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 500}\",Channel $i\nhttp://s/$i.ts\n")
        }
        val epg = gz { w ->
            val base = now - 12 * hour
            val stamps = (0..perChannel).map { fmt.format(base + it * 30 * 60_000L) }
            w.write("<tv>")
            for (c in 0 until epgChannels) {
                for (p in 0 until perChannel) w.write("<programme start=\"${stamps[p]}\" stop=\"${stamps[p + 1]}\" channel=\"c$c\"><title>S$p</title><desc>Programme $p on channel $c</desc></programme>")
            }
            w.write("</tv>")
        }
        e.fake.routes["m.test/big.m3u"] = { playlist }
        e.fake.routes["e.test/big.xml"] = { epg }
        val rt = Runtime.getRuntime()
        var peak = 0L
        val sampler = launch(Dispatchers.Default) { while (true) { peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()); delay(50) } }
        val t0 = System.currentTimeMillis()
        val id = e.core.addAndSync(SourceInput("Big", SourceType.M3U, "http://m.test/big.m3u"))
        val tSync = System.currentTimeMillis() - t0
        assertNull(e.core.source(id)!!.lastError)
        assertEquals(channels.toLong(), e.db.catalogQueries.channelCount(id).executeAsOne())
        assertEquals((epgChannels * perChannel).toLong(), e.db.epgQueries.programmeCount(id).executeAsOne())

        val all = e.core.channels(e.core.group { it.type == GroupType.ALL }, false)
        val t1 = System.currentTimeMillis()
        val rows = e.core.guide(all, now - 2 * hour, now + 2 * hour)
        val tGuide = System.currentTimeMillis() - t1
        assertEquals(50_000, rows.size, "All channels is capped at 50k")
        assertTrue(rows.take(epgChannels.coerceAtMost(50_000)).all { it.programmes.size in 8..9 })
        val t2 = System.currentTimeMillis()
        val hits = e.core.search("channel 1999")
        val tSearch = System.currentTimeMillis() - t2
        assertTrue(hits.channels.isNotEmpty())
        sampler.cancel()
        println("200k channels + 1M programmes: sync ${tSync}ms, guide(50k ch) ${tGuide}ms, search ${tSearch}ms, peak heap ${peak / 1_048_576} MB (gz playlist ${playlist.size / 1024} KB, gz epg ${epg.size / 1024} KB)")
        e.core.close()
    }
}
