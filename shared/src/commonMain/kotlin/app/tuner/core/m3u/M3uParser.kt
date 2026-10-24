package app.tuner.core.m3u

import app.tuner.core.io.LineSplitter
import app.tuner.core.model.ContentKind

data class M3uHeader(
    val epgUrls: List<String>,
    val catchup: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val userAgent: String?,
)

data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val number: Int?,
    val group: String,
    val catchup: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    val userAgent: String?,
    val referrer: String?,
    val headers: Map<String, String>,
    val kind: ContentKind,
)

/**
 * Push parser for extended M3U playlists. Feed it chars as they arrive; it emits
 * one [M3uEntry] per stream URL. Tolerates BOMs, CRLF, missing #EXTM3U, quoted
 * commas in attributes, `url|Header=value` suffixes, and the #EXTVLCOPT, #KODIPROP and
 * #EXTGRP directives playlists commonly use for request headers and groups.
 */
class M3uParser(
    private val onHeader: (M3uHeader) -> Unit = {},
    private val onEntry: (M3uEntry) -> Unit,
) {
    private val lines = LineSplitter(::onLine)
    private var info: Map<String, String> = emptyMap()
    private var title: String? = null
    private var extGroup: String? = null
    private var userAgent: String? = null
    private var referrer: String? = null
    private val extraHeaders = LinkedHashMap<String, String>()
    private var header: M3uHeader? = null
    var entryCount = 0
        private set

    fun feed(chars: CharArray, length: Int) = lines.feed(chars, length)

    fun finish() = lines.finish()

    private fun onLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> parseHeader(line)
            line.startsWith("#EXTINF", ignoreCase = true) -> parseExtInf(line)
            line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> parseOption(line.substring(11))
            line.startsWith("#KODIPROP:", ignoreCase = true) -> parseProperty(line.substring(10))
            line.startsWith("#") -> Unit
            else -> emit(line)
        }
    }

    private fun parseHeader(line: String) {
        val attrs = parseAttributes(line, 7)
        val epg = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"])
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        header = M3uHeader(
            epgUrls = epg,
            catchup = attrs["catchup"],
            catchupSource = attrs["catchup-source"],
            catchupDays = attrs["catchup-days"]?.toIntOrNull(),
            userAgent = attrs["user-agent"] ?: attrs["http-user-agent"],
        ).also(onHeader)
    }

    private fun parseExtInf(line: String) {
        val colon = line.indexOf(':')
        if (colon < 0) return
        // Title starts after the first comma that is outside quotes.
        var inQuotes = false
        var comma = -1
        for (i in colon + 1 until line.length) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { comma = i; break }
        }
        val attrPart = if (comma >= 0) line.substring(0, comma) else line
        info = parseAttributes(attrPart, colon + 1)
        title = if (comma >= 0) line.substring(comma + 1).trim() else null
    }

    private fun parseOption(opt: String) {
        val eq = opt.indexOf('=')
        if (eq < 0) return
        val key = opt.substring(0, eq).trim().lowercase()
        val value = opt.substring(eq + 1).trim()
        when (key) {
            "http-user-agent" -> userAgent = value
            "http-referrer", "http-referer" -> referrer = value
            "http-origin" -> extraHeaders["Origin"] = value
        }
    }

    private fun parseProperty(prop: String) {
        val eq = prop.indexOf('=')
        if (eq < 0) return
        val key = prop.substring(0, eq).trim()
        if (key == "inputstream.adaptive.stream_headers" || key == "inputstream.adaptive.manifest_headers") {
            parseHeaderPairs(prop.substring(eq + 1))
        }
    }

    private fun parseHeaderPairs(pairs: String) {
        for (pair in pairs.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq).trim()
            val v = decodePercent(pair.substring(eq + 1).trim())
            when (k.lowercase()) {
                "user-agent" -> userAgent = v
                "referer", "referrer" -> referrer = v
                else -> extraHeaders[k] = v
            }
        }
    }

    private fun emit(rawUrl: String) {
        var url = rawUrl
        val pipe = url.indexOf('|')
        if (pipe > 0) {
            parseHeaderPairs(url.substring(pipe + 1))
            url = url.substring(0, pipe)
        }
        val tvgName = info["tvg-name"]?.takeIf { it.isNotBlank() }
        val name = title?.takeIf { it.isNotBlank() } ?: tvgName ?: url.substringAfterLast('/')
        val group = info["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: ""
        onEntry(
            M3uEntry(
                name = name,
                url = url,
                tvgId = info["tvg-id"]?.takeIf { it.isNotBlank() },
                tvgName = tvgName,
                logo = (info["tvg-logo"] ?: info["logo"])?.takeIf { it.isNotBlank() },
                number = (info["tvg-chno"] ?: info["channel-number"])?.trim()?.toIntOrNull(),
                group = group,
                catchup = info["catchup"] ?: info["catchup-type"] ?: header?.catchup
                    ?: info["timeshift"]?.let { "default" },
                catchupDays = (info["catchup-days"] ?: info["timeshift"])?.toIntOrNull() ?: header?.catchupDays,
                catchupSource = info["catchup-source"] ?: header?.catchupSource,
                userAgent = userAgent ?: info["user-agent"] ?: header?.userAgent,
                referrer = referrer ?: info["referrer"],
                headers = extraHeaders.toMap(),
                kind = classify(url, group),
            ),
        )
        entryCount++
        info = emptyMap()
        title = null
        extGroup = null
        userAgent = null
        referrer = null
        extraHeaders.clear()
    }

    companion object {
        private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

        fun classify(url: String, group: String): ContentKind {
            val path = url.substringBefore('?').lowercase()
            if ("/series/" in path) return ContentKind.SERIES
            if ("/movie/" in path) return ContentKind.MOVIE
            val ext = path.substringAfterLast('.', "")
            if (ext in VOD_EXTENSIONS) {
                val g = group.lowercase()
                return if ("series" in g || "show" in g) ContentKind.SERIES else ContentKind.MOVIE
            }
            return ContentKind.LIVE
        }

        /** Parses `key="value"` / `key=value` pairs starting at [from]. Keys are lowercased. */
        fun parseAttributes(s: String, from: Int): Map<String, String> {
            val out = HashMap<String, String>()
            var i = from
            val n = s.length
            while (i < n) {
                while (i < n && (s[i] == ' ' || s[i] == '\t')) i++
                val keyStart = i
                while (i < n && s[i] != '=' && s[i] != ' ' && s[i] != '\t') i++
                val key = s.substring(keyStart, i)
                if (i >= n || s[i] != '=') {
                    // bare token such as the duration "-1"
                    if (i == keyStart) i++
                    continue
                }
                i++ // '='
                val value: String
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val end = s.indexOf(q, i + 1).let { if (it < 0) n else it }
                    value = s.substring(i + 1, end)
                    i = end + 1
                } else {
                    val start = i
                    while (i < n && s[i] != ' ' && s[i] != '\t') i++
                    value = s.substring(start, i)
                }
                if (key.isNotEmpty()) out[key.lowercase()] = value.trim()
            }
            return out
        }

        private fun decodePercent(s: String): String {
            if ('%' !in s) return s
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
                }
                c.toString().encodeToByteArray().forEach { bytes.add(it) }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
