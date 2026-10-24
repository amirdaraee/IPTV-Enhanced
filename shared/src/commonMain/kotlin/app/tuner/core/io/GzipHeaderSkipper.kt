package app.tuner.core.io

/** Parses (and skips) a gzip member header so a raw-deflate inflater can take over. */
internal class GzipHeaderSkipper {
    private val buf = ArrayList<Byte>()
    var done = false
        private set

    /** Returns how many bytes of the input belong to the header. */
    fun consume(bytes: ByteArray, offset: Int, length: Int): Int {
        val startSize = buf.size
        for (i in offset until offset + length) {
            buf.add(bytes[i])
            val size = headerSize()
            if (size != null) {
                done = true
                return size - startSize
            }
        }
        return length
    }

    private fun headerSize(): Int? {
        if (buf.size < 10) return null
        val flags = buf[3].toInt() and 0xFF
        var pos = 10
        if (flags and 0x04 != 0) { // FEXTRA
            if (buf.size < pos + 2) return null
            val xlen = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2 + xlen
            if (buf.size < pos) return null
        }
        if (flags and 0x08 != 0) pos = skipZeroTerminated(pos) ?: return null // FNAME
        if (flags and 0x10 != 0) pos = skipZeroTerminated(pos) ?: return null // FCOMMENT
        if (flags and 0x02 != 0) pos += 2 // FHCRC
        return if (buf.size >= pos) pos else null
    }

    private fun skipZeroTerminated(from: Int): Int? {
        for (i in from until buf.size) if (buf[i] == 0.toByte()) return i + 1
        return null
    }
}
