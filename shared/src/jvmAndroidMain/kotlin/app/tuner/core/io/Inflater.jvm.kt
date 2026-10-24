package app.tuner.core.io

import java.util.zip.Inflater

actual class GzipInflater actual constructor() {
    private val inflater = Inflater(true)
    private val out = ByteArray(64 * 1024)
    private val header = GzipHeaderSkipper()

    actual fun feed(bytes: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit) {
        var off = offset
        var len = length
        if (!header.done) {
            val consumed = header.consume(bytes, off, len)
            off += consumed
            len -= consumed
            if (!header.done || len == 0) return
        }
        if (inflater.finished()) return
        inflater.setInput(bytes, off, len)
        while (!inflater.finished()) {
            val n = inflater.inflate(out)
            if (n > 0) sink(out, n)
            else if (inflater.needsInput() || inflater.needsDictionary()) break
        }
    }

    actual fun close() = inflater.end()
}
