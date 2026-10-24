package app.tuner.core.xmltv

interface XmlHandler {
    fun startElement(name: String, attributes: Map<String, String>)

    /** [text] is the decoded character data collected since the matching start tag's last child. */
    fun endElement(name: String, text: String)
}

/**
 * Minimal, allocation-light push XML tokenizer. It is not a validating parser:
 * it understands elements, attributes, text, CDATA, comments, processing
 * instructions and DOCTYPE, which is everything XMLTV files use. Chunks may split
 * anywhere, which lets us parse 100MB+ guides without holding them in memory.
 */
class XmlTokenizer(private val handler: XmlHandler) {
    private enum class S { TEXT, TAG_OPEN, START_NAME, IN_TAG, ATTR_NAME, ATTR_EQ, ATTR_VALUE, END_NAME, BANG, COMMENT, CDATA, DOCTYPE, PI }

    private var state = S.TEXT
    private val text = StringBuilder()
    private val name = StringBuilder()
    private val attrName = StringBuilder()
    private val attrValue = StringBuilder()
    private val bang = StringBuilder()
    private var attrs = HashMap<String, String>()
    private var quote = '"'
    private var selfClosing = false
    private var tail = 0           // matched terminator chars for comment/cdata/pi
    private var doctypeDepth = 0

    fun feed(chars: CharArray, length: Int) {
        for (i in 0 until length) step(chars[i])
    }

    private fun step(c: Char) {
        when (state) {
            S.TEXT -> if (c == '<') state = S.TAG_OPEN else if (text.length < MAX_TEXT) text.append(c)
            S.TAG_OPEN -> when (c) {
                '/' -> { name.clear(); state = S.END_NAME }
                '!' -> { bang.clear(); state = S.BANG }
                '?' -> { tail = 0; state = S.PI }
                else -> {
                    name.clear(); name.append(c)
                    attrs = HashMap()
                    selfClosing = false
                    state = S.START_NAME
                }
            }
            S.START_NAME -> when {
                c == '>' -> openTag()
                c == '/' -> { selfClosing = true; state = S.IN_TAG }
                c.isXmlSpace() -> state = S.IN_TAG
                else -> name.append(c)
            }
            S.IN_TAG -> when {
                c == '>' -> openTag()
                c == '/' -> selfClosing = true
                c.isXmlSpace() -> Unit
                else -> { attrName.clear(); attrName.append(c); state = S.ATTR_NAME }
            }
            S.ATTR_NAME -> when {
                c == '=' -> state = S.ATTR_EQ
                c.isXmlSpace() -> Unit
                c == '>' -> openTag()
                else -> attrName.append(c)
            }
            S.ATTR_EQ -> if (c == '"' || c == '\'') {
                quote = c
                attrValue.clear()
                state = S.ATTR_VALUE
            }
            S.ATTR_VALUE -> if (c == quote) {
                attrs[attrName.toString()] = decodeEntities(attrValue)
                state = S.IN_TAG
            } else {
                attrValue.append(c)
            }
            S.END_NAME -> when {
                c == '>' -> {
                    handler.endElement(name.toString(), decodeEntities(text).trim())
                    text.clear()
                    state = S.TEXT
                }
                c.isXmlSpace() -> Unit
                else -> name.append(c)
            }
            S.BANG -> {
                bang.append(c)
                when {
                    bang.length == 2 && bang.startsWith("--") -> { tail = 0; state = S.COMMENT }
                    bang.length == 7 && bang.toString() == "[CDATA[" -> { tail = 0; state = S.CDATA }
                    bang.length >= 7 || (bang.length == 2 && bang[0] != '-' && bang[0] != '[') -> {
                        doctypeDepth = bang.count { it == '[' }
                        state = if (c == '>' && doctypeDepth == 0) S.TEXT else S.DOCTYPE
                    }
                }
            }
            S.COMMENT -> tail = when {
                c == '-' -> if (tail < 2) tail + 1 else 2
                c == '>' && tail == 2 -> { state = S.TEXT; 0 }
                else -> 0
            }
            S.CDATA -> when {
                c == ']' -> if (tail < 2) tail++ else text.append(']')
                c == '>' && tail == 2 -> { tail = 0; state = S.TEXT }
                else -> {
                    repeat(tail) { text.append(']') }
                    tail = 0
                    text.append(c)
                }
            }
            S.DOCTYPE -> when (c) {
                '[' -> doctypeDepth++
                ']' -> doctypeDepth--
                '>' -> if (doctypeDepth <= 0) state = S.TEXT
                else -> Unit
            }
            S.PI -> tail = when {
                c == '?' -> 1
                c == '>' && tail == 1 -> { state = S.TEXT; 0 }
                else -> 0
            }
        }
    }

    private fun openTag() {
        val n = name.toString()
        text.clear()
        handler.startElement(n, attrs)
        if (selfClosing) handler.endElement(n, "")
        state = S.TEXT
    }

    private fun Char.isXmlSpace() = this == ' ' || this == '\n' || this == '\r' || this == '\t'

    companion object {
        private const val MAX_TEXT = 32 * 1024

        fun decodeEntities(sb: CharSequence): String {
            if (sb.indexOf('&') < 0) return sb.toString()
            val out = StringBuilder(sb.length)
            var i = 0
            while (i < sb.length) {
                val c = sb[i]
                if (c != '&') { out.append(c); i++; continue }
                val semi = sb.indexOf(';', i)
                if (semi < 0 || semi - i > 10) { out.append(c); i++; continue }
                val ent = sb.substring(i + 1, semi)
                val decoded: String? = when {
                    ent == "amp" -> "&"
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePointToString)
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePointToString)
                    else -> null
                }
                if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = semi + 1 }
            }
            return out.toString()
        }

        private fun codePointToString(cp: Int): String = when {
            cp < 0 || cp > 0x10FFFF -> "�"
            cp < 0x10000 -> cp.toChar().toString()
            else -> {
                val v = cp - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }
    }
}
