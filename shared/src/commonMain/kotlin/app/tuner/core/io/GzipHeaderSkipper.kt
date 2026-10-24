package app.tuner.core.io

/**
 * Incrementally parses (and skips) a gzip member header so a raw-deflate
 * inflater can take over. The header is capped at [MAX_HEADER] bytes.
 */
internal class GzipHeaderSkipper {
    private enum class Step { FIXED, EXTRA_LEN, EXTRA, NAME, COMMENT, HCRC, DONE }

    private var step = Step.FIXED
    private var flags = 0
    private var count = 0       // bytes consumed within the current step
    private var extraLen = 0
    private var total = 0
    val done get() = step == Step.DONE

    /** Returns how many bytes of the input belong to the header. */
    fun consume(bytes: ByteArray, offset: Int, length: Int): Int {
        var i = offset
        val end = offset + length
        while (i < end && step != Step.DONE) {
            val b = bytes[i++].toInt() and 0xFF
            if (++total > MAX_HEADER) error("gzip header too large")
            when (step) {
                Step.FIXED -> {
                    if (count == 3) flags = b
                    if (++count == 10) advance(Step.EXTRA_LEN)
                }
                Step.EXTRA_LEN -> {
                    extraLen = extraLen or (b shl (8 * count))
                    if (++count == 2) { if (extraLen == 0) advance(Step.NAME) else step = Step.EXTRA.also { count = 0 } }
                }
                Step.EXTRA -> if (++count == extraLen) advance(Step.NAME)
                Step.NAME -> if (b == 0) advance(Step.COMMENT)
                Step.COMMENT -> if (b == 0) advance(Step.HCRC)
                Step.HCRC -> if (++count == 2) step = Step.DONE
                Step.DONE -> Unit
            }
        }
        return i - offset
    }

    /** Moves to [next], skipping steps whose flag bit is not set. */
    private fun advance(next: Step) {
        count = 0
        var s = next
        while (true) {
            val enabled = when (s) {
                Step.EXTRA_LEN -> flags and 0x04 != 0
                Step.NAME -> flags and 0x08 != 0
                Step.COMMENT -> flags and 0x10 != 0
                Step.HCRC -> flags and 0x02 != 0
                else -> true
            }
            if (enabled) break
            s = when (s) {
                Step.EXTRA_LEN -> Step.NAME
                Step.NAME -> Step.COMMENT
                Step.COMMENT -> Step.HCRC
                else -> Step.DONE
            }
        }
        step = s
    }

    private companion object {
        const val MAX_HEADER = 64 * 1024
    }
}
