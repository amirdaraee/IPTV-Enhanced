package app.tuner.core.io

/** Streaming gzip decompressor backed by the platform zlib. */
expect class GzipInflater() {
    /** Decompresses [length] bytes and passes produced output to [sink]. */
    fun feed(bytes: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit)
    /** True once the end of the compressed stream was reached; false after a cut-off download. */
    val finished: Boolean
    fun close()
}

/**
 * Byte pipeline that transparently gunzips when the stream starts with the gzip
 * magic number, then decodes UTF-8 and hands characters to [sink].
 */
class TextPipeline(private val sink: (CharArray, Int) -> Unit) {
    private val decoder = Utf8Decoder(sink)
    private var inflater: GzipInflater? = null
    private var decided = false
    private var head = ByteArray(0)

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (!decided) {
            // Need two bytes to detect gzip; buffer until we have them.
            val joined = head + bytes.copyOfRange(offset, offset + length)
            if (joined.size < 2) { head = joined; return }
            decided = true
            head = ByteArray(0)
            if (joined[0] == 0x1F.toByte() && joined[1] == 0x8B.toByte()) inflater = GzipInflater()
            route(joined, 0, joined.size)
            return
        }
        route(bytes, offset, length)
    }

    /** Flushes the input; throws if a gzip stream ended early (a dropped or cut-off download). */
    fun finish() {
        if (!decided && head.isNotEmpty()) { decided = true; route(head, 0, head.size) }
        val inf = inflater ?: return
        val complete = inf.finished
        inf.close()
        if (!complete) throw DownloadException("The download was cut off before the end")
    }

    private fun route(bytes: ByteArray, offset: Int, length: Int) {
        val inf = inflater
        if (inf == null) decoder.feed(bytes, offset, length)
        else inf.feed(bytes, offset, length) { out, n -> decoder.feed(out, 0, n) }
    }
}
