package app.familytv.scores

import app.familytv.ui.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreMatchTest {
    private fun team(name: String, full: String, score: String) = ScoreTeam(name, name, full, name.take(3).uppercase(), score)
    private val leafs = ScoreGame("in", "2nd - 12:34", home = team("Canadiens", "Montréal Canadiens", "1"), away = team("Maple Leafs", "Toronto Maple Leafs", "2"))
    private val oilers = ScoreGame("pre", "7:00 PM ET", home = team("Oilers", "Edmonton Oilers", "0"), away = team("Flames", "Calgary Flames", "0"))

    @Test
    fun aGameIsFoundOnlyWhenTheTitleNamesBothTeams() {
        assertEquals(leafs, ScoreMatch.find("NHL: Maple Leafs at Canadiens", listOf(leafs, oilers)))
        assertEquals(leafs, ScoreMatch.find("Toronto Maple Leafs vs. MONTREAL CANADIENS", listOf(leafs, oilers)))
        assertNull(ScoreMatch.find("Hockey Night: Maple Leafs Pre-game", listOf(leafs, oilers)))
        assertNull(ScoreMatch.find("SportsCentre", listOf(leafs, oilers)))
        // Words, not pieces of words.
        assertNull(ScoreMatch.find("Oilerson v Flamesy", listOf(oilers)))
    }

    @Test
    fun twoGamesThatBothFitShowNothing() {
        val other = leafs.copy(detail = "Final")
        assertNull(ScoreMatch.find("Maple Leafs at Canadiens", listOf(leafs, other)))
    }

    @Test
    fun theLineHasTheScoreAwaySideFirstAndNothingBeforeTheStart() {
        assertEquals("Maple Leafs 2 – 1 Canadiens · 2nd - 12:34", ScoreMatch.line(leafs))
        assertEquals("Maple Leafs 2 – 1 Canadiens · Final", ScoreMatch.line(leafs.copy(state = "post", detail = "Final")))
        assertNull(ScoreMatch.line(oilers))
    }

    @Test
    fun onlySportsWithScoreboardsAsk() {
        assertEquals(listOf("nhl"), ScoreMatch.leagues(Sport.Hockey))
        assertEquals(emptyList<String>(), ScoreMatch.leagues(Sport.Golf))
        assertEquals(emptyList<String>(), ScoreMatch.leagues(null))
    }
}
