package app.tuner.core.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.UserAgent

const val DEFAULT_USER_AGENT = "Tuner/0.1 (open-source IPTV player)"

internal fun HttpClientConfig<*>.tunerDefaults() {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = null
    }
    install(ContentEncoding) {
        gzip()
        deflate()
    }
    // Only applied when a request doesn't set its own User-Agent.
    install(UserAgent) { agent = DEFAULT_USER_AGENT }
}
