package app.tuner.core.xmltv

import app.tuner.core.model.Programme

data class XmltvChannel(val id: String, val displayNames: List<String>, val icon: String?)

/**
 * Streams an XMLTV document, emitting channels and programmes as they complete.
 * Feed decoded characters with [feed]; nothing is buffered beyond the current element.
 */
class XmltvParser(
    private val onChannel: (XmltvChannel) -> Unit = {},
    private val onProgramme: (Programme) -> Unit,
) : XmlHandler {
    private val tokenizer = XmlTokenizer(this)

    private var channelId: String? = null
    private val displayNames = ArrayList<String>()
    private var channelIcon: String? = null

    private var progChannel: String? = null
    private var progStart = 0L
    private var progStop = 0L
    private var title: String? = null
    private var desc: String? = null
    private var category: String? = null
    private var progIcon: String? = null
    private var episodeNum: String? = null
    private var episodeSystem: String? = null

    var programmeCount = 0
        private set

    fun feed(chars: CharArray, length: Int) = tokenizer.feed(chars, length)

    override fun startElement(name: String, attributes: Map<String, String>) {
        when (name) {
            "channel" -> {
                channelId = attributes["id"]
                displayNames.clear()
                channelIcon = null
            }
            "programme" -> {
                progChannel = attributes["channel"]
                progStart = parseXmltvTime(attributes["start"]) ?: 0L
                progStop = parseXmltvTime(attributes["stop"]) ?: 0L
                title = null; desc = null; category = null; progIcon = null; episodeNum = null
            }
            "icon" -> if (progChannel != null) progIcon = attributes["src"] else channelIcon = attributes["src"]
            "episode-num" -> episodeSystem = attributes["system"]
        }
    }

    override fun endElement(name: String, text: String) {
        when (name) {
            "display-name" -> if (channelId != null && text.isNotEmpty()) displayNames.add(text)
            "channel" -> {
                channelId?.let { onChannel(XmltvChannel(it, displayNames.toList(), channelIcon)) }
                channelId = null
            }
            // First title/desc/category wins; later ones are usually other languages.
            "title" -> if (title == null && progChannel != null) title = text
            "desc" -> if (desc == null && progChannel != null) desc = text
            "category" -> if (category == null && progChannel != null) category = text
            "episode-num" -> if (progChannel != null && (episodeNum == null || episodeSystem == "onscreen")) {
                episodeNum = if (episodeSystem == "xmltv_ns") formatXmltvNs(text) else text
            }
            "programme" -> {
                val ch = progChannel
                if (ch != null && progStart > 0 && progStop > progStart) {
                    onProgramme(Programme(ch, progStart, progStop, title ?: "", desc, category, progIcon, episodeNum))
                    programmeCount++
                }
                progChannel = null
            }
        }
    }

    companion object {
        /** "0.4.0/1" (zero-based season.episode.part) → "S01E05". */
        fun formatXmltvNs(ns: String): String? {
            val parts = ns.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
            val episode = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
            if (season == null && episode == null) return null
            val s = season?.let { "S" + (it + 1).toString().padStart(2, '0') } ?: ""
            val e = episode?.let { "E" + (it + 1).toString().padStart(2, '0') } ?: ""
            return s + e
        }
    }
}

/**
 * Parses XMLTV timestamps: `YYYYMMDDhhmmss [+-]hhmm`, with optional seconds/minutes
 * and an optional (possibly missing) offset, which is then treated as UTC.
 */
fun parseXmltvTime(value: String?): Long? {
    if (value == null) return null
    val s = value.trim()
    if (s.length < 8) return null
    fun num(from: Int, len: Int): Int? =
        if (s.length >= from + len) s.substring(from, from + len).toIntOrNull() else null
    val year = num(0, 4) ?: return null
    val month = num(4, 2) ?: return null
    val day = num(6, 2) ?: return null
    val hour = num(8, 2) ?: 0
    val minute = num(10, 2) ?: 0
    val second = num(12, 2) ?: 0
    var offsetMinutes = 0
    // The zone follows the digits, which may stop at the minutes ("YYYYMMDDhhmm +zzzz").
    val tz = s.drop(s.takeWhile { it.isDigit() }.length).trim()
    if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
        val h = tz.substring(1, 3).toIntOrNull() ?: 0
        val m = tz.substring(3, 5).toIntOrNull() ?: 0
        offsetMinutes = (h * 60 + m) * if (tz[0] == '-') -1 else 1
    }
    val days = daysFromCivil(year, month, day)
    val secs = days * 86400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
    return secs * 1000L
}

/** Howard Hinnant's days_from_civil: days since 1970-01-01 for a proleptic Gregorian date. */
internal fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}
