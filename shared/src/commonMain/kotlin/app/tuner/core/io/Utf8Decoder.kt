package app.tuner.core.io

/**
 * Incremental UTF-8 decoder: bytes can be fed in arbitrary chunks, multi-byte
 * sequences split across chunk boundaries are carried over. Malformed input is
 * replaced with U+FFFD instead of failing, because real-world playlists are messy.
 */
class Utf8Decoder(private val sink: (CharArray, Int) -> Unit) {
    private var out = CharArray(8192)
    private var pending = 0      // code point being assembled
    private var needed = 0       // continuation bytes still expected
    private var bomChecked = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (out.size < length + 2) out = CharArray(length + 2)
        var n = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i++].toInt() and 0xFF
            if (needed > 0) {
                if (b and 0xC0 == 0x80) {
                    pending = (pending shl 6) or (b and 0x3F)
                    if (--needed == 0) n = emit(pending, n)
                    continue
                }
                out[n++] = '�'
                needed = 0
            }
            when {
                b < 0x80 -> out[n++] = b.toChar()
                b and 0xE0 == 0xC0 -> { pending = b and 0x1F; needed = 1 }
                b and 0xF0 == 0xE0 -> { pending = b and 0x0F; needed = 2 }
                b and 0xF8 == 0xF0 -> { pending = b and 0x07; needed = 3 }
                else -> out[n++] = '�'
            }
        }
        // A byte-order mark decodes to U+FEFF; drop it if it is the first character, which
        // also covers a BOM split across the first network reads.
        var from = 0
        if (!bomChecked && n > 0) {
            bomChecked = true
            if (out[0] == '\uFEFF') from = 1
        }
        if (n > from) {
            if (from > 0) out.copyInto(out, 0, from, n)
            sink(out, n - from)
        }
    }

    private fun emit(cp: Int, n: Int): Int {
        var i = n
        if (cp < 0x10000) {
            out[i++] = cp.toChar()
        } else {
            val v = cp - 0x10000
            out[i++] = (0xD800 + (v shr 10)).toChar()
            out[i++] = (0xDC00 + (v and 0x3FF)).toChar()
        }
        return i
    }
}
