package app.familytv.ui

import app.familytv.data.ProgrammeEntity
import app.familytv.iptv.GuideTitles
import app.familytv.trailer.TvMazeParse
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDetailsTest {
    private fun prog(title: String = "The Simpsons", category: String? = null, desc: String? = null, icon: String? = null) =
        ProgrammeEntity(epgId = "c", start = 0, stop = 1, title = title, description = desc, category = category, icon = icon)

    @Test
    fun factsLine() {
        val p = prog().copy(season = 2, episode = 5, subTitle = "Bart the Genius", category = "Comedy, Animation", rating = "PG", year = 1990, isNew = true)
        assertEquals("S2 E5  ·  Bart the Genius  ·  Comedy, Animation  ·  PG  ·  1990  ·  NEW", programmeFacts(p))
        assertEquals("Episode 7", programmeFacts(prog().copy(episode = 7, subTitle = "The Simpsons")))
        assertNull(programmeFacts(prog()))
    }

    @Test
    fun whatGetsLookedUp() {
        assertTrue(GuideTitles.worthLookingUp("The Simpsons", "Comedy", null, null))
        assertFalse(GuideTitles.worthLookingUp("The Simpsons", "Comedy", "A long enough description of tonight's episode here.", "https://i/x.jpg"))
        assertFalse(GuideTitles.worthLookingUp("CTV News at Six", "News", null, null))
        assertFalse(GuideTitles.worthLookingUp("NHL Hockey: Leafs at Habs", null, null, null))
        assertFalse(GuideTitles.worthLookingUp("To Be Announced", null, null, null))
        assertEquals("The Simpsons", GuideTitles.clean("New: The Simpsons (R)"))
        assertEquals("Ghostbusters", GuideTitles.clean("Ghostbusters 1984"))
    }

    @Test
    fun tvMazeText() {
        assertEquals("A show & more.", TvMazeParse.plain("<p>A <b>show</b> &amp; more.</p>"))
        val m = TvMazeParse.meta(JsonParser.parseString("""{"name":"The Simpsons","summary":"<p>Springfield.</p>","image":{"medium":"http://static.tvmaze.com/m.jpg"}}""").asJsonObject)
        assertEquals("Springfield.", m.summary)
        assertEquals("https://static.tvmaze.com/m.jpg", m.image)
        assertEquals("The Simpsons", m.name)
    }
}
