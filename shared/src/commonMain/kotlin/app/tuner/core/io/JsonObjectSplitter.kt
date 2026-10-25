package app.tuner.core.io

/**
 * Push parser that cuts a JSON document into its second-level objects without
 * building the whole tree: `[{..},{..}]` and `{"1":{..},"2":{..}}` both yield each
 * inner object as text. Xtream catalogs run to tens of megabytes (100k+ movies), far
 * more than a TV's heap can hold as a parsed tree, so each object is handed to
 * [onObject] and parsed on its own. Objects larger than [maxObjectChars] are skipped.
 */
class JsonObjectSplitter(
    private val maxObjectChars: Int = 1 shl 20,
    private val onObject: (String) -> Unit,
) {
    private val current = StringBuilder()
    private var depth = 0
    private var capturing = false
    private var oversized = false
    private var inString = false
    private var escaped = false
    private var started = false

    /** False when the document was opened but never closed, i.e. the body was cut off. */
    val isComplete: Boolean get() = !started || depth == 0

    fun feed(chars: CharArray, length: Int) {
        for (i in 0 until length) {
            val c = chars[i]
            if (capturing && !oversized) {
                if (current.length >= maxObjectChars) {
                    oversized = true
                    current.setLength(0)
                } else {
                    current.append(c)
                }
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    started = true
                    if (depth == 1 && c == '{') {
                        capturing = true
                        oversized = false
                        current.setLength(0)
                        current.append(c)
                    }
                    depth++
                }
                '}', ']' -> {
                    depth--
                    if (depth == 1 && capturing) {
                        capturing = false
                        if (!oversized) onObject(current.toString())
                        current.setLength(0)
                    }
                }
            }
        }
    }
}
