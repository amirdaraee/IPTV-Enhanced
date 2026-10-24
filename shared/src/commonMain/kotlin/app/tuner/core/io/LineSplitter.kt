package app.tuner.core.io

/** Splits a char stream into lines (LF, CRLF or CR), carrying partial lines across chunks. */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val partial = StringBuilder()
    private var lastWasCr = false

    fun feed(chars: CharArray, length: Int) {
        var start = 0
        for (i in 0 until length) {
            val c = chars[i]
            if (c == '\n' || c == '\r') {
                if (c == '\n' && lastWasCr && i == start && partial.isEmpty()) {
                    // second half of CRLF
                    start = i + 1
                    lastWasCr = false
                    continue
                }
                partial.appendRange(chars, start, i)
                onLine(partial.toString())
                partial.clear()
                start = i + 1
                lastWasCr = c == '\r'
            } else {
                lastWasCr = false
            }
        }
        if (start < length) partial.appendRange(chars, start, length)
    }

    fun finish() {
        if (partial.isNotEmpty()) onLine(partial.toString())
        partial.clear()
    }
}
