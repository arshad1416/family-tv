package app.familytv

import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.XtreamParser
import app.familytv.user.EpisodeWatchEntity
import app.familytv.user.NewEpisodeList
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

    @Test fun newEpisodesAreListedOldestFirst() {
        assertEquals(listOf(2 to 1, 2 to 2), EpisodeGuide.after(show, 1 to 3))
        assertEquals(emptyList<Pair<Int, Int>>(), EpisodeGuide.after(show, 2 to 2))
    }

    @Test fun waitingCountsEverythingAfterTheFurthestWatched() {
        // Watched up to S1E2: S1E3, S2E1, S2E2 are waiting, even on a first look.
        assertEquals(listOf(1 to 3, 2 to 1, 2 to 2), EpisodeGuide.waiting(show, emptyList(), null, setOf(1 to 1, 1 to 2)))
        // Nothing watched and a first look: nothing yet, so favouriting a show doesn't light it up.
        assertEquals(emptyList<Pair<Int, Int>>(), EpisodeGuide.waiting(show, emptyList(), null, emptySet()))
        // Nothing watched: only episodes that arrived since the last look.
        assertEquals(listOf(2 to 2), EpisodeGuide.waiting(show, emptyList(), 2 to 1, emptySet()))
        // A listed episode the provider pulled, or one watched since, drops off.
        assertEquals(listOf(2 to 2), EpisodeGuide.waiting(show, listOf(2 to 1, 2 to 2, 9 to 9), null, setOf(2 to 1)))
        // Specials don't count as the furthest watched.
        assertEquals(emptyList<Pair<Int, Int>>(), EpisodeGuide.waiting(show, emptyList(), null, setOf(0 to 1)))
    }

    @Test fun newEpisodeListRoundTripsAndSkipsJunk() {
        assertEquals("1:5,2:1", NewEpisodeList.encode(listOf(1 to 5, 2 to 1)))
        assertEquals(listOf(1 to 5, 2 to 1), NewEpisodeList.decode("1:5,2:1"))
        assertEquals(listOf(3 to 4), NewEpisodeList.decode("x,3:4,:,5"))
        assertEquals(emptyList<Pair<Int, Int>>(), NewEpisodeList.decode(""))
    }

    @Test fun counterCountsDownAsNewEpisodesAreWatched() {
        val w = EpisodeWatchEntity("s", "9", "Show", 2, 3, 0, newCount = 3, newSince = 1, newEpisodes = "2:1,2:2,2:3")
        assertEquals(3, NewEpisodeList.unwatched(w) { _, _ -> false })
        assertEquals(1, NewEpisodeList.unwatched(w) { s, e -> s == 2 && e <= 2 })
        assertEquals(0, NewEpisodeList.unwatched(w) { _, _ -> true })
        // A count saved before episodes were listed stays as it was.
        assertEquals(4, NewEpisodeList.unwatched(w.copy(newCount = 4, newEpisodes = "")) { _, _ -> true })
    }
}
