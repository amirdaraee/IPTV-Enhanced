package app.tuner.core.io

import kotlinx.cinterop.*
import platform.zlib.*

@OptIn(ExperimentalForeignApi::class)
actual class GzipInflater actual constructor() {
    private val stream: z_stream = nativeHeap.alloc()
    private val out = ByteArray(64 * 1024)
    private var done = false
    actual val finished: Boolean get() = done
    private var closed = false

    init {
        // 16 + MAX_WBITS tells zlib to expect a gzip wrapper.
        val rc = inflateInit2_(stream.ptr, 16 + MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
        check(rc == Z_OK) { "inflateInit2 failed: $rc" }
    }

    actual fun feed(bytes: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit) {
        if (done || length == 0) return
        bytes.usePinned { input ->
            out.usePinned { output ->
                stream.next_in = input.addressOf(offset).reinterpret()
                stream.avail_in = length.toUInt()
                do {
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = out.size.toUInt()
                    val rc = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = out.size - stream.avail_out.toInt()
                    if (produced > 0) sink(out, produced)
                    if (rc == Z_STREAM_END) {
                        done = true
                        break
                    }
                    if (rc != Z_OK && rc != Z_BUF_ERROR) error("inflate failed: $rc")
                } while (stream.avail_out == 0u)
            }
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
    }
}
