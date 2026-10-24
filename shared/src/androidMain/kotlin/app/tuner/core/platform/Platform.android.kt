package app.tuner.core.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tuner.core.db.TunerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual class PlatformContext(val context: Context)

internal actual fun createDriver(context: PlatformContext): SqlDriver =
    AndroidSqliteDriver(
        schema = TunerDatabase.Schema,
        context = context.context,
        name = "tuner.db",
        callback = object : AndroidSqliteDriver.Callback(TunerDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }
        },
    )

internal actual fun createHttpClient(context: PlatformContext): HttpClient = HttpClient(OkHttp) { tunerDefaults() }

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main
