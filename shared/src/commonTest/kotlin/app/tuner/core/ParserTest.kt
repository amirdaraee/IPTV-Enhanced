package app.tuner.core

import app.tuner.core.io.TextPipeline
import app.tuner.core.m3u.M3uEntry
import app.tuner.core.m3u.M3uHeader
import app.tuner.core.m3u.M3uParser
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Programme
import app.tuner.core.xmltv.XmltvChannel
import app.tuner.core.xmltv.XmltvParser
import app.tuner.core.xmltv.parseXmltvTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParserTest {
    private fun parseM3u(text: String, chunk: Int = 7): Pair<M3uHeader?, List<M3uEntry>> {
        var header: M3uHeader? = null
        val entries = mutableListOf<M3uEntry>()
        val parser = M3uParser({ header = it }, { entries += it })
        val pipeline = TextPipeline(parser::feed)
        val bytes = text.encodeToByteArray()
        bytes.toList().chunked(chunk).forEach { pipeline.feed(it.toByteArray()) }
        pipeline.finish()
        parser.finish()
        return header to entries
    }

    @Test
    fun parsesExtendedM3u() {
        val (header, entries) = parseM3u(
            "﻿#EXTM3U url-tvg=\"http://epg/a.xml.gz,http://epg/b.xml\" catchup=\"shift\"\r\n" +
                "#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-name=\"BBC One\" tvg-logo=\"http://l/bbc.png\" tvg-chno=\"101\" group-title=\"UK, News\",BBC One HD\r\n" +
                "#EXTVLCOPT:http-user-agent=TestUA/1.0\r\n" +
                "http://s/live/1.ts\r\n" +
                "#EXTINF:0,Movie Night\n#EXTGRP:Films\nhttp://s/movie/u/p/55.mkv\n" +
                "#EXTINF:-1 tvg-id=\"\" catchup-days=\"3\",Ünïcödé 📺\nhttp://s/x.m3u8|User-Agent=TestPlayer%2F2&Referer=http://r/\n",
        )
        assertEquals(listOf("http://epg/a.xml.gz", "http://epg/b.xml"), header?.epgUrls)
        assertEquals(3, entries.size)
        with(entries[0]) {
            assertEquals("BBC One HD", name)
            assertEquals("bbc1.uk", tvgId)
            assertEquals("UK, News", group)
            assertEquals(101, number)
            assertEquals("TestUA/1.0", userAgent)
            assertEquals("shift", catchup)
            assertEquals(ContentKind.LIVE, kind)
        }
        assertEquals("Films", entries[1].group)
        assertEquals(ContentKind.MOVIE, entries[1].kind)
        with(entries[2]) {
            assertEquals("Ünïcödé 📺", name)
            assertNull(tvgId)
            assertEquals(3, catchupDays)
            assertEquals("http://s/x.m3u8", url)
            assertEquals("TestPlayer/2", userAgent)
            assertEquals("http://r/", referrer)
        }
    }

    @Test
    fun parsesXmltvInChunks() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="x"><!-- comment > with -- dashes -->
              <channel id="bbc1.uk"><display-name lang="en">BBC One</display-name><display-name>101</display-name><icon src="http://i/1.png"/></channel>
              <programme start="20261024120000 +0200" stop="20261024130000 +0200" channel="bbc1.uk">
                <title lang="en">News &amp; Weather</title><title lang="fr">Infos</title>
                <desc><![CDATA[Daily <b>news</b>]]></desc>
                <category>News</category>
                <episode-num system="xmltv_ns">0.4.</episode-num>
              </programme>
              <programme start="20261024130000" stop="20261024133000" channel="bbc1.uk"><title>Short &#233;</title></programme>
            </tv>"""
        for (chunk in listOf(1, 3, 64, 100_000)) {
            val channels = mutableListOf<XmltvChannel>()
            val progs = mutableListOf<Programme>()
            val parser = XmltvParser({ channels += it }, { progs += it })
            val pipeline = TextPipeline(parser::feed)
            xml.encodeToByteArray().toList().chunked(chunk).forEach { pipeline.feed(it.toByteArray()) }
            pipeline.finish()
            assertEquals(listOf("BBC One", "101"), channels.single().displayNames)
            assertEquals("http://i/1.png", channels.single().icon)
            assertEquals(2, progs.size, "chunk=$chunk")
            assertEquals("News & Weather", progs[0].title)
            assertEquals("Daily <b>news</b>", progs[0].description)
            assertEquals("S01E05", progs[0].episodeNum)
            assertEquals(parseXmltvTime("20261024100000 +0000"), progs[0].start)
            assertEquals("Short é", progs[1].title)
        }
    }

    @Test
    fun parsesTimestamps() {
        assertEquals(0L, parseXmltvTime("19700101000000 +0000"))
        assertEquals(1_792_843_200_000L, parseXmltvTime("20261024120000 +0000"))
        assertEquals(1_792_843_200_000L, parseXmltvTime("20261024073000 -0430"))
    }

    @Test
    fun classifiesContentKind() {
        assertEquals(ContentKind.MOVIE, M3uParser.classify("http://p/movie/u/p/1.mkv", "Films"))
        assertEquals(ContentKind.SERIES, M3uParser.classify("http://p/series/u/p/2.mp4", "Any"))
        assertEquals(ContentKind.SERIES, M3uParser.classify("http://p/x/3.mp4", "TV Shows"))
        assertEquals(ContentKind.MOVIE, M3uParser.classify("http://p/x/4.mp4?token=1", "Cinema"))
        assertEquals(ContentKind.LIVE, M3uParser.classify("http://p/live/u/p/5.mp4", "Movies"))
        assertEquals(ContentKind.LIVE, M3uParser.classify("https://cdn/play/live_a.flv", "Undefined"))
        assertEquals(ContentKind.LIVE, M3uParser.classify("http://p/6.m3u8", "Movies"))
    }

    @Test
    fun splitsJsonObjectsInChunks() {
        val doc = """[{"a":"x{y}","b":[1,{"n":2}]}, {"s":"q\\\"}"}, 3, {"big":"${"z".repeat(100)}"}]"""
        val objectDoc = """{"1":{"id":1},"2":{"id":2,"e":{}}}"""
        for (chunk in listOf(1, 3, 7, 1000)) {
            val got = ArrayList<String>()
            val splitter = app.tuner.core.io.JsonObjectSplitter(maxObjectChars = 64) { got += it }
            doc.chunked(chunk).forEach { splitter.feed(it.toCharArray(), it.length) }
            assertEquals(listOf("""{"a":"x{y}","b":[1,{"n":2}]}""", """{"s":"q\\\"}"}"""), got, "chunk=$chunk")

            val got2 = ArrayList<String>()
            val s2 = app.tuner.core.io.JsonObjectSplitter { got2 += it }
            objectDoc.chunked(chunk).forEach { s2.feed(it.toCharArray(), it.length) }
            assertEquals(listOf("""{"id":1}""", """{"id":2,"e":{}}"""), got2)
        }
    }
}
