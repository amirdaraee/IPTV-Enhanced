package app.tuner.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.ContentKind
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two devices sharing one cloud copy. Syncs run explicitly so each test fixes the order of events. */
class CloudSyncTest {
    private class Cloud(var text: String? = null, var refuse: Boolean = false) : UserDataCloud {
        var writes = 0
        override fun read() = text
        override fun write(snapshot: String): Boolean {
            if (refuse) return false
            text = snapshot
            writes++
            return true
        }
        override fun delete() { text = null }
    }

    private val databases = HashMap<TunerCore, TunerDatabase>()

    private fun device(cloud: Cloud): TunerCore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TunerDatabase.Schema.create(it) }
        val core = TunerCore(driver, HttpClient(MockEngine { respond("#EXTM3U\n") }))
        core.attachCloudForTest(cloud)
        databases[core] = TunerDatabase(driver)
        return core
    }

    private fun input(name: String, url: String = "http://$name.test/p.m3u") =
        SourceInput(name, SourceType.M3U, url, null, "secret")

    private suspend fun TunerCore.settle() {
        while (sourcesSyncing()) Thread.sleep(10)
        syncCloudNow()
    }

    @Test
    fun aPlaylistAndItsPasswordReachANewDevice() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.settle()
        val b = device(cloud)
        b.settle()
        val s = b.sources().single()
        assertEquals("One", s.name)
        assertEquals("secret", s.password)
    }

    // The failure this guards against loses every device's data: a new install has an empty
    // database, and uploading that would look like the user deleting everything.
    @Test
    fun anEmptyNewDeviceNeverWipesTheCloud() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.settle()
        val before = cloud.text
        assertNotNull(before)

        val other = Cloud()
        device(other).settle()
        assertNull(other.text, "nothing to upload from an empty device")

        val b = device(cloud)
        b.settle()
        assertEquals(1, b.sources().size)
        a.settle()
        assertEquals(1, a.sources().size)
    }

    @Test
    fun aDeletedPlaylistStaysDeletedOnEveryDevice() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val id = a.addSource(input("One"))
        a.settle()
        val b = device(cloud)
        b.settle()
        a.deleteSource(id)
        a.settle()
        b.settle()
        assertTrue(b.sources().isEmpty(), "gone from the other device")
        a.settle()
        assertTrue(a.sources().isEmpty(), "and not brought back")
    }

    @Test
    fun changesOnBothDevicesAreCombined() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val idA = a.addSource(input("One"))
        a.settle()
        val b = device(cloud)
        b.settle()
        val idB = b.sources().single().id
        // Both offline: each favorites something different.
        a.setFavorite(ContentKind.LIVE, idA, "from-a", true)
        b.setFavorite(ContentKind.LIVE, idB, "from-b", true)
        a.settle()
        b.settle()
        a.settle()
        assertEquals(setOf("from-a", "from-b"), favorites(a))
        assertEquals(setOf("from-a", "from-b"), favorites(b))
        assertEquals(a.portableForTest(), b.portableForTest(), "the devices converge")
        val writes = cloud.writes
        a.settle(); b.settle()
        assertEquals(writes, cloud.writes, "no writes once they agree, so devices can't ping-pong")
    }

    @Test
    fun removingAFavoriteReachesTheOtherDevice() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val idA = a.addSource(input("One"))
        a.setFavorite(ContentKind.LIVE, idA, "ch", true)
        a.settle()
        val b = device(cloud)
        b.settle()
        assertEquals(setOf("ch"), favorites(b))
        b.setFavorite(ContentKind.LIVE, b.sources().single().id, "ch", false)
        b.settle()
        a.settle()
        assertEquals(emptySet(), favorites(a))
    }

    @Test
    fun settingsAndEditsFollow() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val id = a.addSource(input("One"))
        a.settle()
        val b = device(cloud)
        b.settle()
        a.updateSettings(a.settings.copy(use24h = !b.settings.use24h))
        a.updateSource(id, input("Renamed").copy(url = "http://One.test/p.m3u", password = "new"))
        a.settle()
        b.settle()
        assertEquals(a.settings.use24h, b.settings.use24h)
        val s = b.sources().single()
        assertEquals("Renamed", s.name)
        assertEquals("new", s.password)
    }

    @Test
    fun theSameAccountOnTwoDevicesIsOnePlaylist() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        val b = device(cloud)
        b.addSource(input("One"))
        a.settle()
        b.settle()
        a.settle()
        assertEquals(1, a.sources().size)
        assertEquals(1, b.sources().size)
    }

    @Test
    fun aRefusedWriteKeepsTheNewerLocalData() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val id = a.addSource(input("One"))
        a.settle()
        cloud.refuse = true
        a.setFavorite(ContentKind.LIVE, id, "ch", true)
        a.settle()
        a.settle()
        assertEquals(setOf("ch"), favorites(a), "not reverted to what the cloud still holds")
        cloud.refuse = false
        a.settle()
        val b = device(cloud)
        b.settle()
        assertEquals(setOf("ch"), favorites(b), "uploaded once the store accepts it")
    }

    @Test
    fun aNewAccountsEmptyCloudKeepsLocalData() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.settle()
        cloud.text = null
        a.settle()
        assertEquals(1, a.sources().size)
        assertNotNull(cloud.text, "uploaded to the new account")
    }

    @Test
    fun aNewerAppsCopyIsLeftAlone() = runBlocking<Unit> {
        val newer = """{"version":99,"sources":[]}"""
        val cloud = Cloud(newer)
        val a = device(cloud)
        a.addSource(input("One"))
        a.settle()
        assertEquals(newer, cloud.text)
        assertEquals(1, a.sources().size)
    }

    @Test
    fun whatIsOnScreenAndThePinLockoutStayOnTheDevice() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val id = a.addSource(input("One"))
        val db = databases.getValue(a).userQueries
        db.putSetting(TunerCore.LAST_CHANNEL, "$id|ch")
        db.putSetting(TunerCore.PIN_LOCKED_UNTIL, "123")
        a.settle()
        val text = assertNotNull(cloud.text)
        assertFalse(TunerCore.LAST_CHANNEL in text)
        assertFalse(TunerCore.PIN_LOCKED_UNTIL in text)
        assertFalse(TunerCore.CLOUD_BASE in text, "the merge base doesn't copy itself into the snapshot")
    }

    // Database ids differ per device, and progress keys and recents embed them.
    @Test
    fun watchHistoryMovesBetweenDifferentDatabaseIds() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val idA = a.addSource(input("One"))
        val dbA = databases.getValue(a).userQueries
        dbA.saveProgress("movie:$idA:m1", idA, "MOVIE", "Film", null, null, "http://x/1", 5_000, 9_000, 100, null)
        dbA.touchRecent(idA, "ch", 100)
        a.settle()

        val b = device(cloud)
        b.deleteSource(b.addSource(input("Temp")))
        b.settle()
        val idB = b.sources().single().id
        assertTrue(idA != idB, "the point of this test")
        val dbB = databases.getValue(b).userQueries
        val p = dbB.allProgress().executeAsList().single()
        assertEquals("movie:$idB:m1", p.pkey)
        assertEquals(idB, p.source_id)
        assertEquals(idB, dbB.allRecents().executeAsList().single().source_id)

        // The newest position wins, in either direction.
        dbB.saveProgress("movie:$idB:m1", idB, "MOVIE", "Film", null, null, "http://x/1", 7_000, 9_000, 200, null)
        b.settle()
        a.settle()
        assertEquals(7_000, dbA.allProgress().executeAsList().single().position_ms)
        assertEquals(1, dbA.allProgress().executeAsList().size, "updated, not added under another key")
    }

    @Test
    fun turningSyncOffStopsBothDirectionsOnThisDeviceOnly() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        val id = a.addSource(input("One"))
        a.settle()
        val b = device(cloud)
        b.settle()

        a.setCloudSyncEnabledNow(false, deleteCloudCopy = false)
        assertFalse(a.cloudSyncEnabled)
        assertTrue(b.cloudSyncEnabled, "the switch itself doesn't sync")
        a.setFavorite(ContentKind.LIVE, id, "private", true)
        a.settle()
        b.setFavorite(ContentKind.LIVE, b.sources().single().id, "from-b", true)
        b.settle()
        a.settle()
        assertEquals(setOf("from-b"), favorites(b), "nothing goes up from the device that is off")
        assertEquals(setOf("private"), favorites(a), "and nothing comes down to it")

        // Back on: combined, and nothing counts as deleted.
        a.setCloudSyncEnabledNow(true, deleteCloudCopy = false)
        b.settle()
        assertEquals(setOf("private", "from-b"), favorites(a))
        assertEquals(setOf("private", "from-b"), favorites(b))
    }

    @Test
    fun deletingTheCloudCopyRemovesTheLoginAndKeepsLocalData() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.settle()
        assertTrue("secret" in assertNotNull(cloud.text))
        a.setCloudSyncEnabledNow(false, deleteCloudCopy = true)
        a.settle()
        assertNull(cloud.text, "stays deleted, because this device no longer uploads")
        assertEquals(1, a.sources().size)
    }

    @Test
    fun theOffSwitchSurvivesLosingTheDatabase() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.setCloudSyncEnabledNow(false, deleteCloudCopy = false)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TunerDatabase.Schema.create(it) }
        val saved = a.exportUserData()
        val restored = TunerCore(driver, HttpClient(MockEngine { respond("#EXTM3U\n") }), object : UserDataBackup {
            override fun save(snapshot: String) {}
            override fun load() = saved
        })
        restored.attachCloudForTest(cloud)
        assertFalse(restored.cloudSyncEnabled)
        restored.settle()
        assertNull(cloud.text, "a restored device doesn't start uploading passwords again")
    }

    @Test
    fun aForgottenPinIsResetByPlaylistSecretsOnly() = runBlocking<Unit> {
        val a = device(Cloud())
        a.addSource(SourceInput("X", SourceType.XTREAM, "http://x.test", "user", "login-pass"))
        a.addSource(input("M"))
        a.setPin("1234")
        assertFalse(a.resetPin("wrong"))
        assertFalse(a.resetPin(""), "an empty guess never matches")
        assertTrue(a.hasPin())
        assertTrue(a.resetPin("login-pass"))
        assertFalse(a.hasPin())

        a.setPin("1234")
        assertFalse(a.resetPin("user"), "the username isn't a secret")
        assertTrue(a.resetPin(" http://M.test/p.m3u "), "an M3U address works, spaces aside")
        assertFalse(a.hasPin())
    }

    @Test
    fun wrongResetGuessesHitTheSameLockout() = runBlocking<Unit> {
        val a = device(Cloud())
        a.addSource(SourceInput("X", SourceType.XTREAM, "http://x.test", "user", "login-pass"))
        a.setPin("1234")
        repeat(5) { assertFalse(a.resetPin("guess$it")) }
        assertTrue(a.pinLockoutRemainingMs() > 0)
        assertFalse(a.resetPin("login-pass"), "even the right one is refused during the lockout")
        assertTrue(a.hasPin())
    }

    @Test
    fun aResetPinIsRemovedOnEveryDevice() = runBlocking<Unit> {
        val cloud = Cloud()
        val a = device(cloud)
        a.addSource(input("One"))
        a.setPin("1234")
        a.settle()
        val b = device(cloud)
        b.settle()
        assertTrue(b.hasPin())
        assertTrue(b.resetPin("http://One.test/p.m3u"))
        b.settle()
        a.settle()
        assertFalse(a.hasPin())
    }

    private fun favorites(core: TunerCore) = core.portableForTest().items.filter { it.favorite }.mapTo(HashSet()) { it.key }
}
