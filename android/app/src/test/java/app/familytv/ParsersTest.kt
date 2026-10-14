package app.familytv

import app.familytv.iptv.M3uParser
import app.familytv.iptv.XmltvParser
import app.familytv.iptv.XtreamParser
import app.familytv.iptv.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ParsersTest {
    @Test
    fun m3uReadsAttributesAndCommasInsideQuotes() {
        val m3u = """
            #EXTM3U x-tvg-url="http://epg.example/guide.xml"
            #EXTINF:-1 tvg-id="cbc.ca" tvg-name="CBC, Toronto" tvg-logo="http://l/cbc.png" group-title="Canada" tvg-chno="5" catchup-days="3",CBC Toronto HD
            http://line.example/live/u/p/101.ts

            #EXTINF:-1,No Attributes
            #EXTGRP:Misc
            http://line.example/live/u/p/102.ts
            #EXTINF:-1 group-title="Films",Movie
            http://line.example/movie/u/p/9.mkv
        """.trimIndent()
        val out = mutableListOf<app.familytv.iptv.M3uEntry>()
        M3uParser.parse(m3u.reader()) { out += it }
        assertEquals(3, out.size)
        assertEquals("CBC Toronto HD", out[0].name)
        assertEquals("Canada", out[0].group)
        assertEquals("cbc.ca", out[0].tvgId)
        assertEquals(5, out[0].chno)
        assertEquals(3, out[0].catchupDays)
        assertEquals("Misc", out[1].group)
        assertNull(out[1].logo)
        assertEquals("http://line.example/movie/u/p/9.mkv", out[2].url)
    }

    @Test
    fun xtreamStreamsToleratesStringNumbersNullsAndObjectLists() {
        val json = """[{"num":1,"name":"CBC","stream_id":101,"stream_icon":"","epg_channel_id":null,"category_id":"7","tv_archive":1,"tv_archive_duration":"5"},
            {"num":"2","name":" CTV ","stream_id":"102","category_id":"7","tv_archive":0}]"""
        val out = mutableListOf<app.familytv.iptv.XtStream>()
        XtreamParser.streams(json.reader()) { out += it }
        assertEquals(listOf("101", "102"), out.map { it.id })
        assertEquals(5, out[0].catchupDays)
        assertNull(out[0].icon)
        assertNull(out[0].epgId)
        assertEquals("CTV", out[1].name)
        assertEquals(2, out[1].num)

        val obj = """{"a":{"series_id":5,"name":"Show","cover":"http://c/1.jpg","category_id":"3"}}"""
        val series = mutableListOf<app.familytv.iptv.XtStream>()
        XtreamParser.streams(obj.reader()) { series += it }
        assertEquals("5", series.single().id)
        assertEquals("http://c/1.jpg", series.single().icon)
    }

    @Test
    fun xtreamAccount() {
        val json = """{"user_info":{"auth":1,"status":"Active","exp_date":"1790000000","active_cons":"1","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"timezone":"America/Toronto"}}"""
        val a = XtreamParser.account(json.reader())
        assertTrue(a.auth)
        assertEquals(1_790_000_000_000L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals("America/Toronto", a.serverTimezone)
        assertFalse(XtreamParser.account("""{"user_info":{"auth":0}}""".reader()).auth)
    }

    @Test
    fun xtreamEpisodesSortedBySeason() {
        val json = """{"info":{"name":"Show"},"episodes":{"2":[{"id":"21","episode_num":1,"title":"Two-one","container_extension":"mkv","info":{"plot":"p"}}],
            "1":[{"id":"12","episode_num":2,"title":"One-two"},{"id":"11","episode_num":1,"title":"One-one"}]}}"""
        val eps = XtreamParser.episodes(json.reader())
        assertEquals(listOf("11", "12", "21"), eps.map { it.id })
        assertEquals("mkv", eps.last().ext)
        assertEquals("p", eps.last().plot)
    }

    @Test
    fun xmltvKeepsOnlyWindowAndWantedChannels() {
        val xml = """<?xml version="1.0"?><tv>
            <channel id="cbc.ca"><display-name>CBC</display-name></channel>
            <programme start="20261014180000 +0000" stop="20261014190000 +0000" channel="cbc.ca"><title lang="en">News</title><desc>Evening news</desc><category>News</category></programme>
            <programme start="20261020180000 +0000" stop="20261020190000 +0000" channel="cbc.ca"><title>Too late</title></programme>
            <programme start="20261014180000 +0000" stop="20261014190000 +0000" channel="other"><title>Unwanted</title></programme>
            <programme start="20261014190000" stop="20261014200000" channel="cbc.ca"><title>No zone</title></programme>
        </tv>"""
        val p = KXmlParser().apply { setInput(xml.reader()) }
        val start = XmltvParser.parseTime("20261014000000 +0000")!!
        val end = XmltvParser.parseTime("20261016000000 +0000")!!
        val out = mutableListOf<app.familytv.iptv.XmltvProgramme>()
        XmltvParser.parse(p, start, end, setOf("cbc.ca")) { out += it }
        assertEquals(listOf("News", "No zone"), out.map { it.title })
        assertEquals("Evening news", out[0].description)
        assertEquals(3_600_000L, out[1].stop - out[1].start)
    }

    @Test
    fun xtreamUrlsEncodeCredentials() {
        assertEquals("http://h:8080/live/a%20b/p%40ss/7.ts", XtreamUrls.live("http://h:8080/", "a b", "p@ss", "7"))
        assertTrue(XtreamUrls.api("http://h", "u", "p&x", "get_series").endsWith("player_api.php?username=u&password=p%26x&action=get_series"))
    }

    @Test
    fun redactHidesCredentials() {
        assertEquals("http://h/live/***/***/7.ts", app.familytv.core.redact("http://h/live/user/pass/7.ts"))
        assertEquals("http://h/player_api.php?username=***&password=***", app.familytv.core.redact("http://h/player_api.php?username=u&password=p"))
    }
}
