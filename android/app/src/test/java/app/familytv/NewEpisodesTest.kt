package app.familytv

import app.familytv.iptv.XtreamParser
import app.familytv.sync.NewEpisodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodesTest {
    private fun ep(season: Int, episode: Int, title: String = "") = XtreamParser.Episode("$season-$episode", season, episode, title, "mp4", null, null)

    private val show = listOf(ep(1, 1), ep(1, 2), ep(2, 1, "The Return"))

    @Test
    fun firstSightOnlySetsTheBaseline() {
        assertTrue(NewEpisodeRules.since(null, show).isEmpty())
    }

    @Test
    fun onlyEpisodesPastTheNewestSeenAreNew() {
        val seen = NewEpisodeRules.code(ep(1, 2))
        assertEquals(listOf(ep(2, 1, "The Return")), NewEpisodeRules.since(seen, show))
        assertTrue(NewEpisodeRules.since(NewEpisodeRules.code(ep(2, 1)), show).isEmpty())
        // Season 2 episode 1 is newer than season 1 episode 99.
        assertTrue(NewEpisodeRules.code(ep(2, 1)) > NewEpisodeRules.code(ep(1, 99)))
    }

    @Test
    fun labelNamesOneEpisodeOrCountsSeveral() {
        assertEquals("S2 E1  ·  The Return", NewEpisodeRules.label(listOf(ep(2, 1, "The Return"))))
        assertEquals("2 new episodes from S1 E2", NewEpisodeRules.label(NewEpisodeRules.since(NewEpisodeRules.code(ep(1, 1)), show)))
    }
}
