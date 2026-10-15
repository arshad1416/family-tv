package app.familytv.user

import app.familytv.FamilyTvApp

/**
 * Marking movies and episodes watched (or not) by hand, so a finished episode isn't played again.
 * With Trakt connected the change goes to Trakt history on the next sync, like a finished scrobble.
 */
class WatchedMarks(private val app: FamilyTvApp) {
    private val dao get() = app.userDb.user()

    suspend fun movie(sourceId: String, streamId: String, watched: Boolean) =
        set(listOf(ProgressEntity(ProgressKey.movie(sourceId, streamId), ProgressKey.MOVIE, sourceId, streamId)), watched)

    /** [episodes] are (season, episode) pairs of one show. */
    suspend fun episodes(sourceId: String, seriesId: String, episodes: List<Pair<Int, Int>>, watched: Boolean) =
        set(episodes.map { (s, e) -> ProgressEntity(ProgressKey.episode(sourceId, seriesId, s, e), ProgressKey.EPISODE, sourceId, seriesId, s, e) }, watched)

    private suspend fun set(fresh: List<ProgressEntity>, watched: Boolean) {
        val now = System.currentTimeMillis()
        val push = app.trakt.signedIn
        val rows = fresh.chunked(500).flatMap { chunk ->
            val existing = dao.progressFor(chunk.map { it.key }).associateBy { it.key }
            chunk.mapNotNull { mark(existing[it.key], it, watched, now, push) }
        }
        if (rows.isEmpty()) return
        dao.upsertProgressRows(rows)
        if (push) app.trakt.sync.soon()
    }

    companion object {
        /**
         * The row after marking, or null when it is already that way. Either way the resume point
         * goes, so a re-watch starts from the top. [push] queues it for Trakt history (add when
         * watched, remove when not).
         */
        fun mark(existing: ProgressEntity?, fresh: ProgressEntity, watched: Boolean, now: Long, push: Boolean): ProgressEntity? {
            if (existing == null && !watched) return null
            if (existing != null && existing.watched == watched && (watched || existing.positionMs == 0L)) return null
            val changed = existing?.watched != watched
            return (existing ?: fresh).copy(
                watched = watched,
                percent = if (watched) 100f else 0f,
                positionMs = 0,
                updatedAt = now,
                pendingHistory = if (changed) push else existing!!.pendingHistory,
            )
        }
    }
}
