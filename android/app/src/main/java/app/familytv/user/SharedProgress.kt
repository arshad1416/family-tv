package app.familytv.user

import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.portal.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watched marks and resume points shared by every TV in the profile, through the portal. Each TV
 * pushes the rows it changed and pulls what the others changed; the later change wins. Works
 * with or without Trakt, which keeps its own sync per account.
 */
class SharedProgress(private val app: FamilyTvApp) {
    private val prefs = app.getSharedPreferences("shared_progress", Context.MODE_PRIVATE)
    private val dao get() = app.userDb.user()
    private val lock = Mutex()
    private var pending: Job? = null

    /** Syncs shortly, folding a burst of changes (a season marked, a pause then stop) into one round. */
    fun soon(delayMs: Long = 3_000) {
        pending?.cancel()
        pending = app.scope.launch {
            delay(delayMs)
            runCatching { sync() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** After a heartbeat: another TV changed something since this one last pulled. */
    fun onSeq(seq: Long?) {
        if (seq != null && seq > prefs.getLong(SEQ, 0)) soon(0)
    }

    suspend fun syncIfNewer(sequence: Long) = lock.withLock {
        if (sequence > prefs.getLong(SEQ, 0) && pull()) push()
    }

    suspend fun sync() = lock.withLock {
        if (pull()) push()
    }

    /** False when the portal doesn't share progress yet; then nothing is pushed either. */
    private suspend fun pull(): Boolean {
        var since = prefs.getLong(SEQ, 0)
        while (true) {
            val page = try {
                app.portal.progressPull(since)
            } catch (e: PortalException) {
                if (e.code == 404) return false else throw e
            }
            if (page.profile != prefs.getString(PROFILE, null)) {
                // A new profile (or the first sync): start over, and give it everything on this TV.
                prefs.edit().putString(PROFILE, page.profile).putLong(SEQ, 0).putLong(PUSHED, 0).apply()
                if (since != 0L) { since = 0; continue }
            }
            val rows = page.rows.orEmpty()
            if (rows.isNotEmpty()) {
                val local = dao.progressFor(rows.map { it.key }).associateBy { it.key }
                val changed = rows.mapNotNull { merge(local[it.key], it.toEntity()) }
                if (changed.isNotEmpty()) dao.upsertProgressRows(changed)
            }
            since = page.seq
            prefs.edit().putLong(SEQ, since).apply()
            if (!page.more) return true
        }
    }

    private suspend fun push() {
        val start = System.currentTimeMillis()
        dao.progressChangedSince(prefs.getLong(PUSHED, 0)).chunked(PUSH_MAX).forEach { chunk ->
            app.portal.progressPush(chunk.map(SharedProgressRow::of))
        }
        prefs.edit().putLong(PUSHED, start).apply()
    }

    companion object {
        private const val PROFILE = "profile"
        private const val SEQ = "seq"
        private const val PUSHED = "pushed"
        private const val PUSH_MAX = 500

        /** The row to save for a pulled [remote], or null when this TV's own is as new. Trakt's pending flag stays this TV's. */
        fun merge(local: ProgressEntity?, remote: ProgressEntity): ProgressEntity? =
            if (local == null || remote.updatedAt > local.updatedAt) remote.copy(pendingHistory = local?.pendingHistory ?: false) else null
    }
}

/** A progress row as the portal stores it. */
data class SharedProgressRow(
    val key: String,
    val kind: String,
    val sourceId: String,
    val itemId: String,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val percent: Float = 0f,
    val watched: Boolean = false,
    val updatedAt: Long = 0,
) {
    fun toEntity() = ProgressEntity(key, kind, sourceId, itemId, season, episode, positionMs, durationMs, percent, watched, updatedAt)

    companion object {
        fun of(p: ProgressEntity) = SharedProgressRow(p.key, p.kind, p.sourceId, p.itemId, p.season, p.episode, p.positionMs, p.durationMs, p.percent, p.watched, p.updatedAt)
    }
}

/** One page of rows other TVs in [profile] changed after a seq. */
data class SharedProgressPage(val profile: String? = null, val rows: List<SharedProgressRow>? = null, val seq: Long = 0, val more: Boolean = false)
