package app.familytv

import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.XtreamParser
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import app.familytv.user.WatchedMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedTest {
    private fun ep(s: Int, e: Int) = XtreamParser.Episode("$s-$e", s, e, "", null, null, null)
    private val show = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1), ep(2, 2), ep(0, 1))
    private val fresh = ProgressEntity(ProgressKey.episode("s", "9", 1, 2), ProgressKey.EPISODE, "s", "9", 1, 2)

    @Test fun markWatchedClearsTheResumePointAndQueuesTrakt() {
        val half = fresh.copy(positionMs = 600_000, durationMs = 1_200_000, percent = 50f)
        val r = WatchedMarks.mark(half, fresh, watched = true, now = 5, push = true)!!
        assertTrue(r.watched)
        assertEquals(100f, r.percent)
        assertEquals(0L, r.positionMs)
        assertEquals(1_200_000L, r.durationMs)
        assertTrue(r.pendingHistory)
        assertEquals(5L, r.updatedAt)
    }

    @Test fun unmarkingQueuesARemovalOnlyWhenItWasWatched() {
        val watched = fresh.copy(watched = true, percent = 100f)
        val r = WatchedMarks.mark(watched, fresh, watched = false, now = 5, push = true)!!
        assertFalse(r.watched)
        assertEquals(0f, r.percent)
        assertTrue(r.pendingHistory)
        // In progress but never watched: reset to the start, nothing for Trakt.
        val half = fresh.copy(positionMs = 1_000, percent = 10f)
        assertFalse(WatchedMarks.mark(half, fresh, watched = false, now = 5, push = true)!!.pendingHistory)
    }

    @Test fun noChangeNeedsNoRow() {
        assertNull(WatchedMarks.mark(null, fresh, watched = false, now = 5, push = true))
        assertNull(WatchedMarks.mark(fresh.copy(watched = true, percent = 100f), fresh, watched = true, now = 5, push = true))
        assertNull(WatchedMarks.mark(fresh, fresh, watched = false, now = 5, push = true))
    }

    @Test fun signedOutMarksStayLocal() =
        assertFalse(WatchedMarks.mark(null, fresh, watched = true, now = 5, push = false)!!.pendingHistory)

    @Test fun upToHereStopsAtTheEpisodeAndSkipsSpecials() {
        assertEquals(listOf("1-1", "1-2", "1-3", "2-1"), EpisodeGuide.upTo(show, ep(2, 1)).map { it.id })
        assertEquals(listOf("1-1"), EpisodeGuide.upTo(show, ep(1, 1)).map { it.id })
        assertEquals(listOf("0-1"), EpisodeGuide.upTo(show, ep(0, 1)).map { it.id })
    }

    @Test fun newEpisodesCountAgainstTheLastOneSeen() {
        assertEquals(2 to 2, EpisodeGuide.latest(show))
        assertNull(EpisodeGuide.latest(listOf(ep(0, 1))))
        assertEquals(2, EpisodeGuide.countAfter(show, 1 to 3))
        assertEquals(0, EpisodeGuide.countAfter(show, 2 to 2))
        assertEquals(2, EpisodeGuide.countAfter(show + listOf(ep(3, 1)), 2 to 1))
    }
}
