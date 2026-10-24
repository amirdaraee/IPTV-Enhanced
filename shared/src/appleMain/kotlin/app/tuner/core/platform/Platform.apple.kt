package app.tuner.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.tuner.core.db.TunerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual class PlatformContext

internal actual fun createDriver(context: PlatformContext): SqlDriver =
    NativeSqliteDriver(TunerDatabase.Schema, "tuner.db")

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main

internal actual fun createHttpClient(context: PlatformContext): HttpClient = HttpClient(Darwin) {
    tunerDefaults()
    engine {
        configureRequest { setAllowsCellularAccess(true) }
    }
}

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
