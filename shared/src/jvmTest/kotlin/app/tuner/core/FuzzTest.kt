package app.tuner.core

import app.tuner.core.catchup.CatchupUrlBuilder
import app.tuner.core.io.JsonObjectSplitter
import app.tuner.core.io.TextPipeline
import app.tuner.core.m3u.M3uEntry
import app.tuner.core.m3u.M3uParser
import app.tuner.core.model.CatchupMode
import app.tuner.core.model.Channel
import app.tuner.core.model.Programme
import app.tuner.core.util.searchNorm
import app.tuner.core.xmltv.XmltvChannel
import app.tuner.core.xmltv.XmltvParser
import app.tuner.core.xmltv.parseXmltvTime
import app.tuner.core.xtream.XtreamAccount
import app.tuner.core.xtream.XtreamClient
import app.tuner.core.xtream.XtreamCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parser-level fuzzing: chunk-boundary invariance, garbage input, and edge cases in the
 * M3U / XMLTV / JSON streaming parsers, search normalisation and catch-up URL building.
 */
class FuzzTest {
    private val rnd = Random(20261025)

    // ------------------------------------------------------------------ helpers

    private fun m3u(bytes: ByteArray, chunks: (Int) -> Int): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        val parser = M3uParser(onEntry = { out += it })
        val pipe = TextPipeline(parser::feed)
        var i = 0
        while (i < bytes.size) {
            val n = chunks(bytes.size - i).coerceIn(1, bytes.size - i)
            pipe.feed(bytes.copyOfRange(i, i + n))
            i += n
        }
        pipe.finish()
        parser.finish()
        return out
    }

    private fun xmltv(bytes: ByteArray, chunks: (Int) -> Int): Pair<List<XmltvChannel>, List<Programme>> {
        val ch = ArrayList<XmltvChannel>()
        val pr = ArrayList<Programme>()
        val parser = XmltvParser({ ch += it }, { pr += it })
        val pipe = TextPipeline(parser::feed)
        var i = 0
        while (i < bytes.size) {
            val n = chunks(bytes.size - i).coerceIn(1, bytes.size - i)
            pipe.feed(bytes.copyOfRange(i, i + n))
            i += n
        }
        pipe.finish()
        return ch to pr
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private val nasty = listOf("", " ", "é", "📺", "سلام", "\"", "'", ",", "|", "=", "&amp;", "\t", "#", "##### X #####", "a,b", "日本")

    private fun randomPlaylist(entries: Int): String = buildString {
        append("﻿#EXTM3U url-tvg=\"http://e/1.xml\"\r\n")
        repeat(entries) { i ->
            val name = "Ch $i ${nasty.random(rnd)}".trim()
            val group = nasty.random(rnd).replace("\"", "")
            append("#EXTINF:-1 tvg-id=\"id$i${nasty.random(rnd).replace("\"", "")}\" tvg-logo=\"http://l/$i,x.png\" group-title=\"$group\",$name")
            append(if (rnd.nextBoolean()) "\r\n" else "\n")
            if (rnd.nextInt(4) == 0) append("#EXTVLCOPT:http-user-agent=UA/$i\n")
            append("http://s.test/live/u/p/$i.ts")
            if (rnd.nextInt(5) == 0) append("|User-Agent=X%2F$i&Referer=http://r/$i")
            append(if (rnd.nextBoolean()) "\r\n" else "\n")
        }
    }

    // ------------------------------------------------------------------ M3U

    /** The result must not depend on how the network split the body. */
    @Test
    fun m3uIsChunkBoundaryInvariant() {
        repeat(20) {
            val bytes = randomPlaylist(60).encodeToByteArray()
            val whole = m3u(bytes) { it }
            // The first read is >= 3 bytes here; a split BOM is covered by m3uBomSplitAcrossFirstReads.
            var first = true
            val random = m3u(bytes) { if (first) { first = false; 3 } else 1 + rnd.nextInt(17) }
            assertEquals(whole, random, "random chunking")
            assertEquals(60, whole.size)
        }
    }

    // Regression: a UTF-8 BOM split across the first two network reads is not stripped, so the
    // "#EXTM3U" header line becomes "﻿#EXTM3U ..." and is emitted as a channel whose URL
    // is the header text (Utf8Decoder only checks for the BOM in the first feed() call).
    @Test
    fun m3uBomSplitAcrossFirstReads() {
        val bytes = "﻿#EXTM3U\n#EXTINF:-1,One\nhttp://a/1.ts\n".encodeToByteArray()
        val whole = m3u(bytes) { it }
        val byteByByte = m3u(bytes) { 1 }
        assertEquals(listOf("http://a/1.ts"), whole.map { it.url })
        assertEquals(whole.map { it.url }, byteByByte.map { it.url }, "BOM delivered one byte at a time")
    }

    @Test
    fun m3uGarbageNeverThrows() {
        repeat(200) {
            val len = rnd.nextInt(0, 4000)
            val bytes = ByteArray(len) { (rnd.nextInt(256)).toByte() }
            // Seed with directive fragments so the interesting branches are reached.
            val text = bytes + listOf("#EXTINF:", "\"", ",", "|", "=", "\n", "#EXTM3U", "#KODIPROP:x=", "%").random(rnd).encodeToByteArray()
            m3u(text) { 1 + rnd.nextInt(100) }
        }
        // Truncated mid-attribute / mid-UTF-8 sequence.
        val good = randomPlaylist(10).encodeToByteArray()
        for (cut in good.indices step 7) m3u(good.copyOf(cut)) { 3 }
    }

    @Test
    fun m3uEdgeCases() {
        val text = """
            #EXTINF:-1 tvg-name="Name, With Comma" group-title="A ""quoted"" group",
            http://a/1.ts
            #EXTINF:-1 tvg-id="x",Title with "quotes", and commas
            http://a/2.ts|User-Agent=Foo%20Bar&Referer=http%3A%2F%2Fr%2F&X-Custom=1%2
            #EXTINF:-1 group-title='Single quoted',S
            http://a/3.ts
            http://a/bare-url-without-extinf.ts
            #EXTINF:-1 tvg-chno="  12 " ,Numbered
            http://a/4.ts
            #EXTINF:-1 tvg-logo="",😀 Emoji 😀
            http://a/5.ts
            #EXTINF:-1 catchup="append" catchup-days="abc",Bad days
            http://a/6.ts
        """.trimIndent()
        val e = m3u(text.encodeToByteArray()) { 5 }
        assertEquals(7, e.size)
        assertEquals("Name, With Comma", e[0].name, "empty title falls back to tvg-name")
        assertEquals("Title with \"quotes\", and commas", e[1].name)
        assertEquals("Foo Bar", e[1].userAgent)
        assertEquals("http://r/", e[1].referrer)
        assertEquals("1%2", e[1].headers["X-Custom"], "a dangling % is kept")
        assertEquals("Single quoted", e[2].group)
        assertEquals("bare-url-without-extinf.ts", e[3].name)
        assertEquals(12, e[4].number)
        assertEquals("😀 Emoji 😀", e[5].name)
        assertEquals(null, e[5].logo)
        assertEquals(null, e[6].catchupDays)
    }

    @Test
    fun m3uHugeLineIsCapped() {
        val big = "#EXTINF:-1 tvg-logo=\"" + "x".repeat(5_000_000) + "\",Huge\nhttp://a/1.ts\n#EXTINF:-1,Next\nhttp://a/2.ts\n"
        val e = m3u(big.encodeToByteArray()) { 65536 }
        // The over-long line is truncated (title lost) but the next entry still parses.
        assertEquals(listOf("http://a/1.ts", "http://a/2.ts"), e.map { it.url })
        assertEquals("Next", e[1].name)
    }

    // ------------------------------------------------------------------ XMLTV

    private fun randomGuide(n: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv generator-info-name=\"x\">")
        append("<!-- a comment with <tags> & stuff -->")
        repeat(n) { i ->
            append("<channel id=\"c$i\"><display-name lang=\"en\">Chan &amp; $i ${nasty.random(rnd).replace("&amp;", "")
                .replace("<", "").replace("&", "&amp;")}</display-name><icon src=\"http://i/$i.png\"/></channel>\n")
        }
        repeat(n * 3) { i ->
            val start = 20261025000000L + (i % 20) * 10000
            append("<programme start=\"$start +0545\" stop=\"${start + 5000} +0545\" channel=\"c${i % n}\">")
            append("<title lang=\"fa\">برنامه $i &lt;live&gt; &#128512;</title>")
            if (rnd.nextBoolean()) append("<desc><![CDATA[Desc ]] with ]]] <b>markup</b> & $i]]></desc>")
            append("<episode-num system=\"xmltv_ns\">0.${i % 10}.0/1</episode-num>")
            append("</programme>\n")
        }
        append("</tv>")
    }

    @Test
    fun xmltvIsChunkBoundaryInvariant() {
        repeat(10) {
            val bytes = randomGuide(20).encodeToByteArray()
            val whole = xmltv(bytes) { it }
            val random = xmltv(bytes) { 1 + rnd.nextInt(13) }
            val gz = xmltv(gzip(bytes)) { 1 + rnd.nextInt(50) }
            assertEquals(whole, random)
            assertEquals(whole, gz)
            assertEquals(20, whole.first.size)
            assertEquals(60, whole.second.size)
            val p = whole.second.first { it.description != null }
            assertTrue(p.description!!.startsWith("Desc ]] with ]]] <b>markup</b> & "), p.description)
            assertTrue(whole.second.first().title.endsWith("<live> 😀"), whole.second.first().title)
        }
    }

    @Test
    fun xmltvGarbageAndTruncationNeverThrow() {
        val good = randomGuide(5).encodeToByteArray()
        for (cut in good.indices step 11) xmltv(good.copyOf(cut)) { 7 }
        repeat(200) {
            val s = buildString {
                repeat(rnd.nextInt(1, 300)) {
                    append(listOf("<", ">", "</", "/>", "<!--", "-->", "<![CDATA[", "]]>", "<?", "?>", "<!DOCTYPE [", "]", "\"", "'", "=",
                        "&", ";", "&#x", "&#", "programme", "channel", " start=\"20260101000000\"", " stop=\"20260101010000\"",
                        "title", "x", " ", "\n", "&#xD800;", "&#0;", "&#99999999999;").random(rnd))
                }
            }
            xmltv(s.encodeToByteArray()) { 1 + rnd.nextInt(30) }
        }
        // Truncated gzip: must not hang, and is reported as a cut-off download (so a partial
        // guide never replaces the stored one) rather than failing in some other way.
        val gz = gzip(good)
        for (cut in listOf(5, 10, 11, 20, gz.size / 2, gz.size - 9)) {
            runCatching { xmltv(gz.copyOf(cut)) { 64 } }.exceptionOrNull()?.let {
                assertTrue(it is app.tuner.core.io.DownloadException, "unexpected ${it::class.simpleName}: ${it.message}")
            }
        }
    }

    @Test
    fun xmltvHugeTextStaysBounded() {
        val desc = "d".repeat(10_000_000)
        val xml = "<tv><programme start=\"20260101000000 +0000\" stop=\"20260101010000 +0000\" channel=\"a\"><title>T</title><desc>$desc</desc></programme>" +
            "<programme start=\"20260101010000 +0000\" stop=\"20260101020000 +0000\" channel=\"a\"><title>U</title></programme></tv>"
        val (_, p) = xmltv(xml.encodeToByteArray()) { 65536 }
        assertEquals(listOf("T", "U"), p.map { it.title })
        assertTrue(p[0].description!!.length <= 32 * 1024, "description is capped")
    }

    // Regression: XMLTV allows the seconds to be omitted ("YYYYMMDDhhmm +zzzz"); the parser always
    // reads the offset from column 14, so for a 12-digit time the offset is silently dropped
    // (the programme is shifted by the zone offset). xmltv/XmltvParser.kt parseXmltvTime, `s.drop(14)`.
    @Test
    fun xmltvTimeWithoutSecondsKeepsOffset() {
        val withSeconds = parseXmltvTime("20261025023000 +0100")
        val withoutSeconds = parseXmltvTime("202610250230 +0100")
        assertEquals(withSeconds, withoutSeconds)
    }

    @Test
    fun xmltvTimeVariants() {
        val utc = ZonedDateTime.of(2026, 10, 24, 20, 45, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(utc, parseXmltvTime("20261025023000 +0545"), "Nepal +05:45")
        assertEquals(utc, parseXmltvTime("20261025023000+0545"), "no space")
        assertEquals(utc + 5 * 3_600_000L + 45 * 60_000L, parseXmltvTime("20261025023000"), "no offset = UTC")
        assertEquals(utc + 5 * 3_600_000L + 45 * 60_000L, parseXmltvTime("20261025023000 Z"))
        assertEquals(null, parseXmltvTime("garbage"))
        assertEquals(null, parseXmltvTime(""))
        // Must not throw on odd input.
        listOf("2026", "20261", "2026102", "2026-10-25T02:30:00Z", "20261325999999 +9999", "20261025023000 +", "20261025023000 +01").forEach { parseXmltvTime(it) }
    }

    @Test
    fun xmltvMissingOrBadTimesAreDropped() {
        val xml = """<tv>
            <programme channel="a" start="20260101000000 +0000"><title>No stop</title></programme>
            <programme channel="a" start="20260101010000 +0000" stop="20260101000000 +0000"><title>Backwards</title></programme>
            <programme start="20260101010000 +0000" stop="20260101020000 +0000"><title>No channel</title></programme>
            <programme channel="a" start="20260101010000 +0000" stop="20260101020000 +0000"><title>OK</title></programme>
            <programme channel="a" start="20260101013000 +0000" stop="20260101030000 +0000"><title>Overlap</title></programme>
        </tv>"""
        val (_, p) = xmltv(xml.encodeToByteArray()) { 9 }
        assertEquals(listOf("OK", "Overlap"), p.map { it.title })
    }

    /** Malformed (unquoted) attributes must not swallow the valid programmes that follow. */
    @Test
    fun xmltvUnquotedAttributeDoesNotEatFollowingProgrammes() {
        val xml = "<tv><programme start=20260101000000 stop=20260101010000 channel=a><title>Bad</title></programme>" +
            (1..5).joinToString("") { "<programme start=\"202601010${it}0000 +0000\" stop=\"2026010110${it}0000 +0000\" channel=\"b\"><title>Good $it</title></programme>" } +
            "</tv>"
        val (_, p) = xmltv(xml.encodeToByteArray()) { it }
        assertEquals((1..5).map { "Good $it" }, p.filter { it.channelEpgId == "b" }.map { it.title })
    }

    // ------------------------------------------------------------------ JSON splitter

    @Test
    fun jsonSplitterIsChunkInvariantAndStringSafe() {
        fun split(text: String, chunks: (Int) -> Int): List<String> {
            val out = ArrayList<String>()
            val s = JsonObjectSplitter { out += it }
            val c = text.toCharArray()
            var i = 0
            while (i < c.size) {
                val n = chunks(c.size - i).coerceIn(1, c.size - i)
                s.feed(c.copyOfRange(i, i + n), n)
                i += n
            }
            return out
        }
        val objs = (0 until 200).map { i ->
            """{"stream_id":$i,"name":"N {$i} [x] \"q\" \\","nested":{"a":[1,{"b":"}"}]},"e":[]}"""
        }
        val asArray = objs.joinToString(",", "[", "]")
        val asMap = objs.mapIndexed { i, o -> "\"$i\":$o" }.joinToString(",", "{", "}")
        assertEquals(objs, split(asArray) { it })
        assertEquals(objs, split(asArray) { 1 + rnd.nextInt(9) })
        assertEquals(objs, split(asMap) { 1 + rnd.nextInt(9) })
        // garbage must not throw
        repeat(200) { split(String(CharArray(rnd.nextInt(500)) { "{}[]\",:\\ab1".random(rnd) })) { 1 + rnd.nextInt(20) } }
    }

    // ------------------------------------------------------------------ search normalisation

    @Test
    fun searchNormBasics() {
        assertEquals("cafe", searchNorm("Café"))
        assertEquals("bbc one hd", searchNorm("  BBC---One  [HD] "))
        assertEquals("", searchNorm("%%__"))
        assertEquals("100", searchNorm("100%"))
    }

    // Regression (Persian/Arabic users): Arabic and Persian spellings of the same letters
    // (ي/ی, ك/ک) are not folded, and Arabic diacritics (harakat) split words, so a query typed
    // on a different keyboard layout, or a name with tashkeel, does not match. util/Text.kt searchNorm.
    @Test
    fun searchNormFoldsArabicAndPersianVariants() {
        assertEquals(searchNorm("شبکه یک"), searchNorm("شبكه يك"), "Persian keheh/yeh vs Arabic kaf/yeh")
        assertEquals(searchNorm("محمد"), searchNorm("مُحَمَّد"), "harakat must not split the word")
        assertEquals(searchNorm("café"), searchNorm("café"), "NFD vs NFC")
    }

    // ------------------------------------------------------------------ catch-up URLs

    private fun channel(mode: CatchupMode, url: String = "http://h/live/u/p/7.ts", source: String? = null) = Channel(
        1, 1, "k", "g", "n", null, null, null, null, url, "7", mode, 7, source, null, null, false, false, false,
    )

    @Test
    fun xtreamCatchupAcrossBerlinDstEnd() {
        val client = XtreamClient(HttpClient(MockEngine { respond("") }), XtreamCredentials("http://h", "u", "p"))
        fun stamp(utc: String): String {
            val ms = java.time.Instant.parse(utc).toEpochMilli()
            return CatchupUrlBuilder.build(channel(CatchupMode.XTREAM), ms, ms + 3_600_000, ms + 7_200_000, client, "Europe/Berlin")!!
        }
        // 2026-10-25 is the last Sunday of October: 03:00 CEST -> 02:00 CET.
        assertTrue(stamp("2026-10-24T22:30:00Z").contains("/60/2026-10-25:00-30/7.ts"), stamp("2026-10-24T22:30:00Z"))
        assertTrue(stamp("2026-10-25T00:30:00Z").contains("/2026-10-25:02-30/"))
        assertTrue(stamp("2026-10-25T01:30:00Z").contains("/2026-10-25:02-30/"), "the repeated hour maps to the same wall time")
        assertTrue(stamp("2026-10-25T02:30:00Z").contains("/2026-10-25:03-30/"))
        // Crossing midnight UTC on the server's date.
        assertTrue(stamp("2026-12-31T23:30:00Z").contains("/2027-01-01:00-30/"))
        // A fixed offset derived from the server clock must be a valid zone for the builder.
        val tz = XtreamAccount(null, null, null, null, false, emptyList(), "Bogus/Zone", "2026-10-25 08:15:00", 1792908000L).resolvedTimezone()
        val ms = 1792908000_000L
        val url = CatchupUrlBuilder.build(channel(CatchupMode.XTREAM), ms, ms + 1_800_000, ms, client, tz)!!
        assertTrue(url.contains("/30/2026-10-25:08-15/"), "$tz -> $url")
    }

    // Regression (minor): `{offset:N}` (offset divided by N seconds, the counterpart of `{duration:N}`)
    // is left in the URL unsubstituted. catchup/CatchupUrlBuilder.kt substitute().
    @Test
    fun catchupTemplatePlaceholders() {
        val start = 1792908000L
        val s = CatchupUrlBuilder.substitute("a={utc}&b={utcend}&c={duration:60}&d={Y}{m}{d}{H}{M}{S}&e={utc:Y-m-d H:M:S}&f=\${start}", start, start + 3600, start + 7200)
        assertEquals("a=$start&b=${start + 3600}&c=60&d=20261025060000&e=2026-10-25 06:00:00&f=$start", s)
        val o = CatchupUrlBuilder.substitute("o={offset:60}", start, start + 3600, start + 7200)
        assertFalse('{' in o, o)
    }

    @Test
    fun flussonicAndShiftUrls() {
        val now = 1792908000_000L
        val f = CatchupUrlBuilder.build(channel(CatchupMode.FLUSSONIC, "http://h/ch/index.m3u8?token=a"), now - 3_600_000, now, now, null, null)
        assertEquals("http://h/ch/index-${now / 1000 - 3600}-3600.m3u8?token=a", f)
        val s = CatchupUrlBuilder.build(channel(CatchupMode.SHIFT, "http://h/x.ts?a=1"), now - 60_000, now, now, null, null)
        assertNotNull(s)
        assertTrue(s.startsWith("http://h/x.ts?a=1&utc="), s)
    }
}
