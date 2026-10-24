package app.tuner.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tuner.core.db.TunerDatabase
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.SynchronousFlag
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual class PlatformContext

// WAL (SQLiter's default) with synchronous=NORMAL: commits skip the fsync, which is safe in
// WAL mode and makes batched sync writes much cheaper. Extra reader connections let the
// UI query while a sync holds the writer.
internal actual fun createDriver(context: PlatformContext): SqlDriver =
    NativeSqliteDriver(
        schema = TunerDatabase.Schema,
        name = "tuner.db",
        maxReaderConnections = 3,
        onConfiguration = { config: DatabaseConfiguration ->
            config.copy(extendedConfig = config.extendedConfig.copy(synchronousFlag = SynchronousFlag.NORMAL))
        },
    )

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main

internal actual fun createHttpClient(context: PlatformContext): HttpClient = HttpClient(Darwin) {
    tunerDefaults()
    engine {
        configureRequest { setAllowsCellularAccess(true) }
    }
}

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
