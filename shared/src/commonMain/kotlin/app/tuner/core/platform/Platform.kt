package app.tuner.core.platform

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient

/** Android: wraps a Context. Apple: no state. JVM: optional DB path (null = in-memory). */
expect class PlatformContext

internal expect fun createDriver(context: PlatformContext): SqlDriver

internal expect fun createHttpClient(context: PlatformContext): HttpClient

/** Where the backup copy of the user's own data is kept, or null for none. */
internal expect fun createBackup(context: PlatformContext): app.tuner.core.UserDataBackup?

/** Dispatcher on which observer callbacks are delivered (the UI thread on devices). */
internal expect val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher

internal expect fun nowMillis(): Long
