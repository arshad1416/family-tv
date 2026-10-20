package app.familytv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EventStatusTest {
    @Test
    fun theBadgeFollowsWhatTheProgrammeSaysItIs() {
        assertEquals(EventStatus.Live, EventStatus.of("NHL: Maple Leafs at Canadiens"))
        assertEquals(EventStatus.Ended, EventStatus.of("ENDED: Arsenal v Spurs"))
        assertEquals(EventStatus.Ended, EventStatus.of("Arsenal 2-1 Spurs (FT)"))
        assertEquals(EventStatus.Replay, EventStatus.of("Replay: UFC 300"))
        assertEquals(EventStatus.Replay, EventStatus.of("Premier League Highlights"))
        assertEquals(EventStatus.Upcoming, EventStatus.of("Coming up: Arsenal v Spurs"))
        assertEquals(EventStatus.Upcoming, EventStatus.of("Raptors v Celtics - Starts at 7:30 PM"))
        assertEquals(null, EventStatus.of(null))
    }

    @Test
    fun wordsInsideNamesDontCount() {
        // Whole words only: "Kraftwerk" has no FT and "Encores" is a club, not an encore.
        assertEquals(EventStatus.Live, EventStatus.of("Kraftwerk Live"))
        assertEquals(EventStatus.Live, EventStatus.of("Encores FC v Rovers"))
    }
}
