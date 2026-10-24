package app.tuner.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tuner.core.db.TunerDatabase
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.SynchronousFlag
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

actual class PlatformContext

// WAL (SQLiter's default) with synchronous=NORMAL: commits skip the fsync, which is safe in
// WAL mode and makes batched sync writes much cheaper. Extra reader connections let the
// UI query while a sync holds the writer.
internal actual fun createDriver(context: PlatformContext): SqlDriver {
    val dir = databaseDirectory()
    return NativeSqliteDriver(
        schema = TunerDatabase.Schema,
        name = DB_NAME,
        maxReaderConnections = 3,
        onConfiguration = { config: DatabaseConfiguration ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = dir, synchronousFlag = SynchronousFlag.NORMAL))
        },
    )
}

private const val DB_NAME = "tuner.db"

/**
 * A real Apple TV only lets apps write to Caches and tmp (the simulator allows more), so
 * SQLiter's default of Application Support fails with SQLITE_CANTOPEN on hardware. The
 * catalog is re-synced from the provider if the system ever purges it. A database left in
 * the old location (simulator installs) is moved over once.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun databaseDirectory(): String {
    val fm = NSFileManager.defaultManager
    fun dirIn(directory: ULong) = (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String) + "/databases"
    val dir = dirIn(NSCachesDirectory)
    fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val old = dirIn(NSApplicationSupportDirectory)
    if (!fm.fileExistsAtPath("$dir/$DB_NAME") && fm.fileExistsAtPath("$old/$DB_NAME")) {
        for (suffix in listOf("", "-wal", "-shm")) {
            if (fm.fileExistsAtPath("$old/$DB_NAME$suffix")) fm.moveItemAtPath("$old/$DB_NAME$suffix", "$dir/$DB_NAME$suffix", null)
        }
    }
    return dir
}

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main

internal actual fun createHttpClient(context: PlatformContext): HttpClient = HttpClient(Darwin) {
    tunerDefaults()
    engine {
        configureRequest { setAllowsCellularAccess(true) }
    }
}

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
