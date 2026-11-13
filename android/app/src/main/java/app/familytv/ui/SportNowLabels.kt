package app.familytv.ui

import app.familytv.portal.PortalClient
import app.familytv.portal.SportNowItem

/**
 * Jev's answers for programmes the word lists couldn't place, asked through the portal and kept for
 * the app's lifetime (keyed like the portal's cache, [SportNow.key]). A failed call answers nothing
 * and is tried again on the next re-sort; a portal without Jev answers null for everything.
 */
class SportNowLabels(private val portal: PortalClient) {
    private val known = HashMap<String, String?>()

    /** Labels for [items] (title to genres), keyed by [SportNow.key]; unknown ones are left out. */
    suspend fun labelsFor(items: List<Pair<String, String?>>, descriptions: Map<String, String?> = emptyMap()): Map<String, String?> {
        val wanted = items.distinctBy { SportNow.key(it.first, it.second) }
        val missing = synchronized(known) { wanted.filter { SportNow.key(it.first, it.second) !in known } }.take(MAX_ASK)
        if (missing.isNotEmpty()) {
            val sent = missing.map { (t, g) -> SportNowItem(t.take(200), descriptions[SportNow.key(t, g)]?.take(300), g?.take(200)) }
            runCatching { portal.sportNow(sent) }.getOrNull()?.labels?.let { labels ->
                synchronized(known) {
                    if (known.size > MAX_KEPT) known.clear()
                    missing.forEachIndexed { i, (t, g) -> known[SportNow.key(t, g)] = labels.getOrNull(i)?.label }
                }
            }
        }
        return synchronized(known) { wanted.mapNotNull { (t, g) -> SportNow.key(t, g).let { k -> if (k in known) k to known[k] else null } }.toMap() }
    }

    private companion object {
        /** The portal takes 200 a call; a line's sports channels rarely show more at once. */
        const val MAX_ASK = 200
        const val MAX_KEPT = 5_000
    }
}
