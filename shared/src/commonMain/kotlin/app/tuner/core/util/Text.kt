package app.tuner.core.util

private val ACCENTS: Map<Char, String> = buildMap {
    fun put(chars: String, base: String) = chars.forEach { put(it, base) }
    put("àáâãäåāăą", "a"); put("çćĉċč", "c"); put("ďđ", "d"); put("èéêëēĕėęě", "e")
    put("ĝğġģ", "g"); put("ĥħ", "h"); put("ìíîïĩīĭįı", "i"); put("ĵ", "j"); put("ķ", "k")
    put("ĺļľŀł", "l"); put("ñńņňŉ", "n"); put("òóôõöøōŏő", "o"); put("ŕŗř", "r")
    put("śŝşšș", "s"); put("ţťŧț", "t"); put("ùúûüũūŭůűų", "u"); put("ŵ", "w"); put("ýÿŷ", "y")
    put("źżž", "z"); put("ß", "ss"); put("æ", "ae"); put("œ", "oe"); put("þ", "th")
}

/** Arabic and Persian spellings of the same letter, folded to the Persian form. */
private val ARABIC: Map<Char, String> = mapOf('ي' to "ی", 'ى' to "ی", 'ك' to "ک", 'ة' to "ه", 'أ' to "ا", 'إ' to "ا", 'آ' to "ا")

/** Arabic diacritics (harakat), superscript alef and tatweel: dropped, not turned into spaces. */
private fun isArabicMark(ch: Char) = ch in '\u064B'..'\u065F' || ch == '\u0670' || ch == '\u0640'

/**
 * Lowercase, accent-free, single-spaced text for LIKE searches. Arabic and Persian letter
 * variants are folded, so a name spelled with either keyboard matches the other.
 */
fun searchNorm(s: String): String {
    val sb = StringBuilder(s.length)
    var space = false
    for (ch in s.lowercase()) {
        if (isArabicMark(ch)) continue
        val mapped = ACCENTS[ch] ?: ARABIC[ch]
        when {
            mapped != null -> { sb.append(mapped); space = false }
            ch.isLetterOrDigit() -> { sb.append(ch); space = false }
            else -> if (!space && sb.isNotEmpty()) { sb.append(' '); space = true }
        }
    }
    return sb.toString().trimEnd()
}

private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "hq", "lq", "1080p", "720p", "raw", "backup", "vip")
private val PREFIX = Regex("^\\s*(\\[[^]]{1,12}]|\\|[^|]{1,12}\\||[a-z]{2,4}\\s*[:|-])\\s*", RegexOption.IGNORE_CASE)

/** Aggressive normalisation used to match channel names to XMLTV display names. */
fun epgNorm(s: String): String {
    var t = s
    repeat(2) { t = PREFIX.replace(t, "") }
    return searchNorm(t).split(' ').filter { it.isNotEmpty() && it !in QUALITY }.joinToString("")
}

fun splitUrls(s: String): List<String> = s.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
