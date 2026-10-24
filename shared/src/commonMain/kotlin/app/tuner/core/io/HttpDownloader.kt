package app.tuner.core.io

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

class DownloadException(message: String) : Exception(message)

/**
 * Streams a URL through [TextPipeline] into [sink] without buffering the body.
 * [afterChunk] runs between network reads (outside the parser), which is where
 * callers flush batched database writes.
 */
internal suspend fun HttpClient.streamText(
    url: String,
    userAgent: String?,
    sink: (CharArray, Int) -> Unit,
    afterChunk: suspend () -> Unit = {},
) {
    prepareGet(url) {
        userAgent?.let { header(HttpHeaders.UserAgent, it) }
    }.execute { response ->
        if (!response.status.isSuccess()) throw DownloadException("HTTP ${response.status.value} for ${redact(url)}")
        val channel = response.bodyAsChannel()
        val pipeline = TextPipeline(sink)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            if (n > 0) {
                pipeline.feed(buffer, 0, n)
                afterChunk()
            }
        }
        pipeline.finish()
        afterChunk()
    }
}

/** Removes credentials from URLs before they reach logs or error messages. */
fun redact(url: String): String = url
    .replace(Regex("(username|password)=[^&]*"), "$1=***")
    .replace(Regex("/(live|movie|series|timeshift)/[^/]+/[^/]+/"), "/$1/***/***/")
