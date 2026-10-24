package app.tuner.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tuner.core.db.TunerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual class PlatformContext(val dbPath: String? = null, val httpClient: HttpClient? = null, val backup: app.tuner.core.UserDataBackup? = null)

internal actual fun createDriver(context: PlatformContext): SqlDriver {
    val url = context.dbPath?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY
    return JdbcSqliteDriver(url, schema = TunerDatabase.Schema)
}

internal actual fun createHttpClient(context: PlatformContext): HttpClient = context.httpClient ?: HttpClient(OkHttp) { tunerDefaults() }

internal actual fun createBackup(context: PlatformContext): app.tuner.core.UserDataBackup? = context.backup

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
