package app.familytv

import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import app.familytv.user.SharedProgress
import app.familytv.user.SharedProgressRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedProgressTest {
    private val key = ProgressKey.episode("src1", "77", 1, 4)
    private fun row(updatedAt: Long, watched: Boolean = false, positionMs: Long = 0, pending: Boolean = false) =
        ProgressEntity(key, ProgressKey.EPISODE, "src1", "77", 1, 4, positionMs, 2_600_000, if (watched) 100f else 40f, watched, updatedAt, pending)

    @Test
    fun takesAnotherTvsLaterChange() {
        val merged = SharedProgress.merge(row(1_000, positionMs = 900_000), row(2_000, watched = true))!!
        assertTrue(merged.watched)
        assertEquals(2_000, merged.updatedAt)
        assertEquals(row(2_000, watched = true), SharedProgress.merge(null, row(2_000, watched = true)))
    }

    @Test
    fun keepsThisTvsOwnNewerOrEqualChange() {
        assertNull(SharedProgress.merge(row(3_000, positionMs = 900_000), row(2_000, watched = true)))
        assertNull(SharedProgress.merge(row(2_000), row(2_000))) // its own row coming back
    }

    @Test
    fun keepsThisTvsTraktQueue() {
        // Watched here while Trakt was unreachable: still owed to Trakt after another TV's newer pause.
        assertTrue(SharedProgress.merge(row(1_000, watched = true, pending = true), row(2_000))!!.pendingHistory)
    }

    @Test
    fun wireRowRoundTrips() {
        val r = row(5_000, positionMs = 123_000)
        assertEquals(r, SharedProgressRow.of(r).toEntity())
    }
}
