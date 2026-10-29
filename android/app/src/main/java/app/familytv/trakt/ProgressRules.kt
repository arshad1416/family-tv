package app.familytv.trakt

import app.familytv.user.ProgressEntity
import kotlin.math.roundToLong

/** When something counts as watched and where to pick it up again. Pure, unit tested. */
object ProgressRules {
    /** Trakt marks a scrobble as watched from 80%; we use the same line so devices agree. */
    const val WATCHED_PERCENT = 80f

    /** Less than this in, starting over is nicer than a resume. */
    private const val MIN_RESUME_MS = 30_000L

    fun percent(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs * 100f / durationMs).coerceIn(0f, 100f)

    fun isWatched(percent: Float) = percent >= WATCHED_PERCENT

    /**
     * Where to start, or null for the beginning. Another device's resume point only has a
     * percentage (Trakt keeps no position), so it becomes a position once the duration is known.
     * Only the percentage decides: something watched before still resumes a rewatch.
     */
    fun resumeAt(p: ProgressEntity?, durationMs: Long): Long? {
        if (p == null || isWatched(p.percent)) return null
        val at = if (durationMs > 0 && p.percent > 0f) (durationMs * p.percent / 100f).toLong() else p.positionMs
        if (at < MIN_RESUME_MS) return null
        return if (durationMs > 0) at.coerceAtMost(durationMs - 5_000).takeIf { it >= MIN_RESUME_MS } else at
    }

    /** A remote resume point only replaces ours when it was saved later. */
    fun remoteIsNewer(remotePausedAt: Long, local: ProgressEntity?): Boolean = local == null || remotePausedAt > local.updatedAt

    /** Part-watched, so Play reads Resume: a progress bar, or a saved spot on a stream with no known length. */
    fun started(p: ProgressEntity?): Boolean = bar(p) > 0f || (p != null && p.durationMs <= 0 && resumeAt(p, 0) != null)

    /** "42 min left" or "1 h 5 min left" for a started, unwatched title of known length; null otherwise. */
    fun timeLeft(p: ProgressEntity?): String? {
        if (p == null || p.durationMs <= 0 || bar(p) <= 0f) return null
        val left = (p.durationMs * (100.0 - p.percent) / 100.0 / 60_000).roundToLong().coerceAtLeast(1)
        return when {
            left < 60 -> "$left min left"
            left % 60 == 0L -> "${left / 60} h left"
            else -> "${left / 60} h ${left % 60} min left"
        }
    }

    /** Fraction for the progress line on a poster or episode (0 when there is none to show). */
    fun bar(p: ProgressEntity?): Float = if (p == null || isWatched(p.percent)) 0f else (p.percent / 100f).coerceIn(0f, 1f)

    /**
     * Continue watching, newest first: the started, unwatched movies or episodes of [kind]
     * ([app.familytv.user.ProgressKey.MOVIE] or [app.familytv.user.ProgressKey.EPISODE]), one per film or show (its latest).
     * A rewatch of something marked watched resumes, but stays out of it, so no poster there shows both the check and
     * the time left (round 22).
     */
    fun continueWatching(rows: Collection<ProgressEntity>, kind: String): List<ProgressEntity> =
        rows.filter { it.kind == kind && !it.watched && started(it) }.sortedByDescending { it.updatedAt }.distinctBy { it.sourceId to it.itemId }
}
