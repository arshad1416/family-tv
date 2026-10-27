package app.familytv.ui

import app.familytv.FamilyTvApp
import app.familytv.core.Http
import app.familytv.trailer.Tmdb
import app.familytv.trailer.TmdbHit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TMDB's search for what was typed, to order the line's matches by how well known they are
 * ([app.familytv.data.PopularOrder]). Only the typed words go to TMDB, with the portal's key; each
 * answer is kept for this run, so going back to a search asks again for nothing.
 */
internal object TmdbSearch {
    private const val KEPT = 40
    private const val WAIT_MS = 4_000L
    private val answers = object : LinkedHashMap<String, List<TmdbHit>>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TmdbHit>>?) = size > KEPT
    }

    private fun key(q: String, tv: Boolean) = "${if (tv) "tv" else "movie"}/${q.trim().lowercase()}"

    fun kept(q: String, tv: Boolean): List<TmdbHit>? = synchronized(answers) { answers[key(q, tv)] }

    /** TMDB's films ([tv] false) or shows for [q], or null without a TMDB key, offline or too slow. */
    suspend fun hits(app: FamilyTvApp, q: String, tv: Boolean): List<TmdbHit>? {
        kept(q, tv)?.let { return it }
        val apiKey = app.portal.cachedConfig?.tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val found = withTimeoutOrNull(WAIT_MS) { runCatching { Tmdb(apiKey, Http.client).search(q.trim(), tv) }.getOrNull() } ?: return null
        synchronized(answers) { answers[key(q, tv)] = found }
        return found
    }
}
