package app.familytv

import app.familytv.data.NowNext
import app.familytv.data.ProgrammeEntity
import app.familytv.iptv.GuideMatch
import app.familytv.iptv.GuideMatch.How
import app.familytv.iptv.XmltvChannel
import app.familytv.iptv.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class GuideMatchTest {
    private fun ch(id: String) = XmltvChannel(id, listOf(GuideMatch.idName(id)))

    // Ids as epgshare01 writes them (CA2, US2, IN1).
    private val ca = GuideMatch.Index(
        listOf(
            "CTV.Toronto.HD.ca2", "CTV.Toronto.ca2", "CTV.Vancouver.HD.ca2", "CBC.Toronto.HD.ca2", "TSN.4K.ca2", "TSN.HD.ca2",
            "Discovery.Channel.ca2", "ATN.Punjabi.ca2", "Cable.Pulse.24.(CP24).HD.ca2", "Sportsnet.(Pacific).ca2",
        ).map(::ch),
    )
    private val mixed = GuideMatch.Index(listOf("Discovery.Channel.ca2", "Discovery.Channel.HD.us2").map(::ch))

    private fun link(name: String, epgId: String? = null, category: String? = null, index: GuideMatch.Index = ca, pin: String? = null) =
        GuideMatch.link(GuideMatch.Channel(name, epgId, category), index, pin)

    @Test
    fun keysDropPrefixesAndPictureQuality() {
        assertEquals("ctvtoronto", GuideMatch.key("CA| CTV Toronto HD"))
        assertEquals("ctvtoronto", GuideMatch.key("[CA] CTV TORONTO ᴴᴰ"))
        assertEquals("discoverychannel", GuideMatch.key("The Discovery Channel"))
        assertEquals("ctv+1", GuideMatch.key("CTV +1"))
        assertEquals("CTV Toronto HD", GuideMatch.idName("CTV.Toronto.HD.ca2"))
        assertEquals("Discovery HD", GuideMatch.idName("Discovery.HD"))
    }

    @Test
    fun countryAndLanguagePrefixesComeOffAndTheCountryIsTheFirstWord() {
        assertEquals("ctvnewschannel", GuideMatch.key("CA EN: CTV NEWS CHANNEL HD"))
        assertEquals("ca", GuideMatch.countryOfName("CA EN: CTV NEWS CHANNEL HD"))
        assertEquals("us", GuideMatch.countryOfName("US EN| CNN"))
        val news = GuideMatch.Index(listOf("CTV.News.Channel.HD.ca2", "CTV.News.Channel.us2").map(::ch))
        assertEquals("CTV.News.Channel.HD.ca2", link("CA EN: CTV NEWS CHANNEL HD", index = news)?.guideId)
    }

    @Test
    fun globalNewsFeedsLinkToTheGuidesGlobalNames() {
        val global = GuideMatch.Index(listOf("Global.BC.ca2", "Global.Toronto.HD.ca2").map(::ch))
        assertEquals("Global.BC.ca2", link("CA| GLOBAL NEWS BC HD", index = global)?.guideId)
        assertEquals("Global.Toronto.HD.ca2", link("CA EN: Global News Toronto", index = global)?.guideId)
        assertNull(link("US| GLOBAL NEWS BC", index = global))
    }

    @Test
    fun countriesFromIdsNamesAndCategories() {
        assertEquals("ca", GuideMatch.countryOfId("CTV.Toronto.HD.ca2"))
        assertEquals("us", GuideMatch.countryOfId("cnn.us"))
        assertNull(GuideMatch.countryOfId("Discovery.HD"))
        assertEquals("us", GuideMatch.countryOfName("USA| Discovery"))
        assertEquals("ca", GuideMatch.countryOfName("CANADA ENTERTAINMENT"))
        assertNull(GuideMatch.countryOfName("ATN Punjabi"))
    }

    @Test
    fun linksByPinThenIdThenName() {
        assertEquals(GuideMatch.Link("CTV.Toronto.HD.ca2", How.ID), link("Whatever", epgId = "CTV.Toronto.HD.ca2"))
        assertEquals(How.ID_NAME, link("Whatever", epgId = "ctvtoronto.ca")?.how)
        assertEquals(GuideMatch.Link("CTV.Toronto.HD.ca2", How.NAME), link("CA| CTV TORONTO HD"))
        assertEquals("ATN.Punjabi.ca2", link("ATN PUNJABI", category = "Punjabi")?.guideId)
        assertEquals("TSN.4K.ca2", link("TSN 4K")?.guideId)
        assertEquals("TSN.HD.ca2", link("CA: TSN")?.guideId)
        assertEquals("Cable.Pulse.24.(CP24).HD.ca2", link("CA| CP24")?.guideId)
        assertEquals(GuideMatch.Link("CTV.Vancouver.HD.ca2", How.PIN), link("CA| CTV HD", pin = "CTV.Vancouver.HD.ca2"))
        // A pin to another guide's channel keeps this guide from matching by name.
        assertNull(link("CA| CTV Toronto HD", pin = "CTV.Toronto.HD.us2"))
    }

    @Test
    fun neverGuessesBetweenRegionsOrCountries() {
        // "CTV" alone could be any city's CTV.
        assertNull(link("CA| CTV HD"))
        assertNull(link("CA| CBC"))
        // A US channel never takes a Canadian schedule, and a bare name used by two countries links to neither.
        assertNull(link("US| Discovery Channel"))
        assertNull(link("Discovery Channel", index = mixed))
        assertEquals("Discovery.Channel.HD.us2", link("Discovery Channel", category = "USA Documentaries", index = mixed)?.guideId)
        assertEquals("Discovery.Channel.ca2", link("CA| Discovery Channel", index = mixed)?.guideId)
        assertNull(link("CTV +1"))
        // "(Pacific)" is a region, not another name: plain "Sportsnet" stays unlinked.
        assertNull(link("CA| Sportsnet"))
    }

    private val h = 3_600_000L

    @Test
    fun coverageCountsRealTitlesOnly() {
        fun nn(title: String, from: Long, to: Long) = NowNext("e", title, from * h, to * h)
        assertEquals(1f, GuideMatch.coverage(listOf(nn("News", 0, 12), nn("Movie", 12, 30)), "CTV", 0, 24 * h), 0.001f)
        assertEquals(0.5f, GuideMatch.coverage(listOf(nn("News", 0, 12), nn("No information", 12, 24)), "CTV", 0, 24 * h), 0.001f)
        assertEquals(0f, GuideMatch.coverage(listOf(nn("CTV HD", 0, 24)), "CA| CTV HD", 0, 24 * h), 0.001f)
        // Overlapping slots are counted once.
        assertEquals(0.5f, GuideMatch.coverage(listOf(nn("A", 0, 12), nn("B", 6, 12)), "X", 0, 24 * h), 0.001f)
        assertFalse(GuideMatch.isRealTitle("To Be Announced", "X"))
        assertTrue(GuideMatch.isRealTitle("The Simpsons", "X"))
    }

    @Test
    fun fillCopiesOnlyMissingFieldsFromTheSameShow() {
        val p1 = ProgrammeEntity(id = 1, epgId = "ctv", start = 0, stop = h, title = "The Simpsons", description = "Bart.", rating = "PG")
        val p2 = ProgrammeEntity(id = 2, epgId = "ctv", start = h, stop = 2 * h, title = "News", description = null)
        val x1 = ProgrammeEntity(epgId = "x:c", start = 2 * 60_000, stop = h, title = "New: The Simpsons", description = "Bart swaps tests with Martin.",
            subTitle = "Bart the Genius", season = 2, episode = 5, rating = "14+", icon = "https://img/s.jpg")
        val x2 = ProgrammeEntity(epgId = "x:c", start = h + 10 * 60_000, stop = 2 * h, title = "News", description = "Too late a start.")
        val out = GuideMatch.fill(listOf(p1, p2), listOf(x1, x2))
        assertEquals(1, out.size)
        val f = out[0]
        assertEquals(1L, f.id)
        assertEquals("Bart the Genius", f.subTitle)
        assertEquals(2, f.season)
        assertEquals("Bart swaps tests with Martin.", f.description)
        assertEquals("PG", f.rating) // the provider's own value stays
        assertEquals("https://img/s.jpg", f.icon)
    }

    @Test
    fun parserHandsOverChannelsBeforeTheFirstProgramme() {
        val xml = """<?xml version="1.0"?><tv>
            <channel id="CTV.Toronto.HD.ca2"><icon src="http://i/1.png"/><display-name lang="en">CTV Toronto HD</display-name></channel>
            <channel id="TSN.HD.ca2"><display-name>TSN HD</display-name><display-name>TSN</display-name></channel>
            <programme start="20261014180000 +0000" stop="20261014190000 +0000" channel="CTV.Toronto.HD.ca2"><title>CTV News</title></programme>
            <programme start="20261014180000 +0000" stop="20261014190000 +0000" channel="TSN.HD.ca2"><title>SportsCentre</title></programme>
        </tv>"""
        val channels = mutableListOf<XmltvChannel>()
        val wanted = HashSet<String>()
        var seenAtStart = -1
        val out = mutableListOf<String>()
        XmltvParser.parse(
            KXmlParser().apply { setInput(xml.reader()) }, 0, Long.MAX_VALUE, wanted,
            onChannel = channels::add,
            beforeProgrammes = { seenAtStart = channels.size; wanted += "TSN.HD.ca2" },
        ) { out += it.title }
        assertEquals(2, seenAtStart)
        assertEquals(listOf("TSN HD", "TSN"), channels[1].names)
        assertEquals(listOf("SportsCentre"), out)
    }
}
