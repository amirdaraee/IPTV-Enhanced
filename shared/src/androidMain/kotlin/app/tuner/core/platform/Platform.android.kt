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
                // Safe in WAL mode and avoids an fsync per batched sync commit.
                db.query("PRAGMA synchronous = NORMAL").use { it.moveToFirst() }
            }
        },
    )

internal actual fun createHttpClient(context: PlatformContext): HttpClient = HttpClient(OkHttp) { tunerDefaults() }

/**
 * The database itself is safe in app storage; this small file is what Android's backup to
 * Google includes (see the app's backup rules), so a reinstall or a new TV gets it back.
 */
internal actual fun createBackup(context: PlatformContext): app.tuner.core.UserDataBackup? = FileBackup(java.io.File(context.context.filesDir, "user-data.json"))

private class FileBackup(private val file: java.io.File) : app.tuner.core.UserDataBackup {
    override fun save(snapshot: String) {
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(snapshot)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    override fun load(): String? = file.takeIf { it.exists() }?.readText()
}

internal actual val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main
