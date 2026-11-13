package app.familytv.ui

import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.OnAir
import app.familytv.scores.ScoreGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportNowTest {
    private fun on(title: String, genres: String? = null, live: Boolean = false, repeat: Boolean = false) =
        OnAir("e", title, null, genres, 0, 1, live, repeat)

    @Test
    fun programmeTitlesNameTheirSport() {
        assertEquals(Sport.Hockey, SportNow.byRules("NHL Hockey: Maple Leafs at Canadiens", null))
        assertEquals(Sport.Basketball, SportNow.byRules("Raptors vs Knicks", "Sports, Basketball"))
        assertEquals(Sport.Combat, SportNow.byRules("UFC 320: Main Card", "Sports event"))
        assertNull(SportNow.byRules("Leafs vs Canadiens", "Sports event")) // nothing names the sport: ask Jev
    }

    @Test
    fun studioProgrammesAreShows() {
        assertEquals(Sport.Shows, SportNow.byRules("SportsCentre", null))
        assertEquals(Sport.Shows, SportNow.byRules("NHL Tonight", null))
        assertEquals(Sport.Shows, SportNow.byRules("Premier League Highlights", null))
        assertEquals(Sport.Shows, SportNow.byRules("Off the Record", "Sports talk"))
        // A game whose title has show words is still a game.
        assertEquals(Sport.Hockey, SportNow.byRules("NHL Pre-Season Review Cup: Leafs vs Habs", null))
    }

    @Test
    fun aBareFootballGenreIsLeftToJev() {
        assertNull(SportNow.byRules("Bills at Dolphins", "Football"))
        assertEquals(Sport.Soccer, SportNow.byRules("Arsenal v Chelsea", "Soccer"))
    }

    @Test
    fun portalLabelsMapToSports() {
        assertEquals(Sport.Hockey, SportNow.fromLabel("Hockey"))
        assertEquals(Sport.Shows, SportNow.fromLabel("Show"))
        listOf("NotSport", "Unsure", "More", "Shows", "Curling", null).forEach { assertNull(it, SportNow.fromLabel(it)) }
    }

    @Test
    fun liveAndReplayTags() {
        assertEquals(AirTag.Live, SportNow.tag(on("Leafs at Habs", live = true), null))
        assertEquals(AirTag.Live, SportNow.tag(on("LIVE: Leafs at Habs"), null))
        assertEquals(AirTag.Replay, SportNow.tag(on("Leafs at Habs", repeat = true), null))
        assertEquals(AirTag.Replay, SportNow.tag(on("NHL Classics: 1993 Leafs at Kings"), null))
        assertEquals(AirTag.Live, SportNow.tag(on("Leafs at Habs"), ScoreGame(state = "in")))
        assertEquals(AirTag.Replay, SportNow.tag(on("Leafs at Habs"), ScoreGame(state = "post")))
        assertNull(SportNow.tag(on("Leafs at Habs"), ScoreGame(state = "pre")))
        assertNull(SportNow.tag(on("Leafs at Habs"), null))
    }

    @Test
    fun placementPrefersWhatIsOnNow() {
        // TSN 1 has no sport in its name; tonight it's showing hockey.
        assertEquals(SportSpot(Sport.Hockey, AirTag.Live), SportNow.place("TSN 1", "CA| SPORTS EN", on("NHL: Leafs at Habs", live = true), null))
        // The rules can't tell; Jev says basketball.
        assertEquals(SportSpot(Sport.Basketball), SportNow.place("TSN 1", "CA| SPORTS EN", on("Raptors at Knicks"), "Basketball"))
        // Jev unsure, or nothing in the guide: the channel's own name decides.
        assertEquals(SportSpot(Sport.More), SportNow.place("TSN 1", "CA| SPORTS EN", on("Raptors at Knicks"), "Unsure"))
        assertEquals(SportSpot(Sport.More), SportNow.place("TSN 1", "CA| SPORTS EN", on("No Information"), "Hockey"))
        assertEquals(SportSpot(Sport.Hockey), SportNow.place("Sportsnet NHL", "CA| SPORTS EN", null, null))
        // Studio shows go to Shows & Highlights without a tag.
        assertEquals(SportSpot(Sport.Shows), SportNow.place("TSN 1", "CA| SPORTS EN", on("LIVE SportsCentre", live = true), null))
        // A PPV channel with no sport in its name joins once its event names one.
        assertNull(SportNow.place("PPV 01", "US| PPV EVENTS", null, null))
        assertEquals(SportSpot(Sport.Combat), SportNow.place("PPV 01", "US| PPV EVENTS", on("UFC 320"), null))
    }

    @Test
    fun askableSkipsEmptyGuideEntries() {
        assertTrue(SportNow.askable("Leafs at Habs"))
        listOf("No Information", "TBA", " ", "Off Air").forEach { assertFalse(it, SportNow.askable(it)) }
    }

    @Test
    fun keyMatchesThePortals() {
        assertEquals("leafs at canadiens|sports, ice hockey", SportNow.key("  Leafs   at Canadiens ", "Sports, Ice Hockey"))
    }

    @Test
    fun groupsCarryTags() {
        val cats = listOf(CategoryEntity("s", "live", "1", "CA| SPORTS EN", 0, 0))
        fun ch(id: String, name: String) = ChannelEntity("s", id, name, 1, null, "1", null, null, 0, 0)
        val g = groupBySportNow(cats, listOf(ch("a", "TSN 1"), ch("b", "TSN 2"))) { c, _ ->
            if (c.streamId == "a") SportSpot(Sport.Hockey, AirTag.Live) else SportSpot(Sport.Shows)
        }
        assertEquals(listOf(Sport.Hockey, Sport.Shows), g.bySport.keys.toList())
        assertEquals(mapOf("s/a" to AirTag.Live), g.tags)
    }
}
