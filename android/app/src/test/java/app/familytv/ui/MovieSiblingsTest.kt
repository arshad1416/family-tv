package app.familytv.ui

import app.familytv.trakt.ProgressRules
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 22: a film's marks on one copy show on its others, and a watched film leaves Continue watching. */
class MovieSiblingsTest {
    private fun movie(stream: String, percent: Float, watched: Boolean, at: Long) =
        ProgressEntity(ProgressKey.movie("s", stream), ProgressKey.MOVIE, "s", stream, durationMs = 6_000_000, percent = percent, watched = watched, updatedAt = at)

    private val a = ProgressKey.movie("s", "a")
    private val b = ProgressKey.movie("s", "b")
    private val c = ProgressKey.movie("s", "c")
    private val copies = mapOf(a to listOf(b, c), b to listOf(a, c))

    @Test
    fun aCopyWithNoRowTakesTheFilmsLatest() {
        val started = movie("a", 40f, false, 10)
        val marks = withSiblings(listOf(started), copies)
        assertSame(started, marks[c])
        assertSame(started, marks[b])
    }

    @Test
    fun aWatchedCopyMakesTheWholeFilmWatched() {
        val seen = movie("a", 100f, true, 10)
        val started = movie("b", 40f, false, 20)
        val marks = withSiblings(listOf(seen, started), copies)
        assertTrue(listOf(a, b, c).all { marks.getValue(it).watched })
        assertTrue(ProgressRules.continueWatching(marks.values, ProgressKey.MOVIE).isEmpty())
    }

    @Test
    fun startedCopiesKeepTheirOwnResumePoints() {
        val first = movie("a", 40f, false, 10)
        val second = movie("b", 60f, false, 20)
        val marks = withSiblings(listOf(first, second), copies)
        assertSame(first, marks[a])
        assertSame(second, marks[b])
        assertSame(second, marks[c])
    }

    @Test
    fun aRewatchStaysOutOfContinueWatching() {
        assertEquals(emptyList<ProgressEntity>(), ProgressRules.continueWatching(listOf(movie("a", 40f, true, 10)), ProgressKey.MOVIE))
    }

    /** Round 23: Novocaine's tile said Watched while its page offered "Resume · 1 h 48 min left". */
    @Test
    fun aWatchedFilmsPagePlaysAgainFromTheStart() {
        val rewatch = movie("a", 2f, true, 10)
        assertEquals("▶  Play again", moviePlayLabel(rewatch))
        assertNull(movieResumeAt(rewatch))
        assertEquals("▶  Resume · 1 h left", moviePlayLabel(movie("a", 40f, false, 10)))
        assertEquals("▶  Play", moviePlayLabel(null))
    }
}
