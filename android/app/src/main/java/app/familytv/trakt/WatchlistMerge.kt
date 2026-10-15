package app.familytv.trakt

/** Ids for one movie or show. Any shared id means the same title. */
data class MediaIds(val tmdb: String? = null, val imdb: String? = null, val trakt: Long? = null) {
    val isEmpty get() = tmdb == null && imdb == null && trakt == null

    fun matches(o: MediaIds): Boolean =
        (tmdb != null && tmdb == o.tmdb) || (imdb != null && imdb == o.imdb) || (trakt != null && trakt == o.trakt)
}

/**
 * Two-way merge of movie and series favourites with the Trakt watchlist. Pure: the caller
 * reads both sides, asks for a [Plan], then applies it.
 *
 * - A change made here ([LocalFav.pending]) wins and is pushed.
 * - Something that was on Trakt at the last sync but is gone now was removed on another
 *   device, so it is removed here too.
 * - Anything on Trakt that no favourite here matches was added elsewhere and is pulled in.
 * - A favourite with no ids (no TMDB id and no search hit yet) stays local until it can be matched.
 */
object WatchlistMerge {
    enum class Op { ADD, REMOVE }

    data class LocalFav<K>(val key: K, val kind: String, val ids: MediaIds, val pending: Op?, val onTrakt: Boolean)
    data class Remote(val kind: String, val ids: MediaIds, val title: String, val year: Int?)

    data class Plan<K>(
        /** Send to the watchlist, then mark synced. */
        val pushAdd: List<LocalFav<K>> = emptyList(),
        /** Remove from the watchlist, then delete the tombstone. */
        val pushRemove: List<LocalFav<K>> = emptyList(),
        /** Already in the state we want on Trakt: mark synced (pending cleared, onTrakt set). */
        val markSynced: List<K> = emptyList(),
        /** Delete locally: removed elsewhere, or a tombstone with nothing left to remove. */
        val deleteLocal: List<K> = emptyList(),
        /** On Trakt but not here: find in the catalog and add. */
        val pull: List<Remote> = emptyList(),
    )

    fun <K> plan(local: List<LocalFav<K>>, remote: List<Remote>): Plan<K> {
        val pushAdd = ArrayList<LocalFav<K>>()
        val pushRemove = ArrayList<LocalFav<K>>()
        val markSynced = ArrayList<K>()
        val deleteLocal = ArrayList<K>()
        val claimed = BooleanArray(remote.size)
        // A remove can't go out twice for the same title (two sources carrying one movie).
        val removing = ArrayList<MediaIds>()

        fun onTrakt(f: LocalFav<K>): Boolean {
            var hit = false
            remote.forEachIndexed { i, r -> if (r.kind == f.kind && !f.ids.isEmpty && r.ids.matches(f.ids)) { claimed[i] = true; hit = true } }
            return hit
        }

        for (f in local) {
            val there = onTrakt(f)
            when {
                f.pending == Op.REMOVE -> when {
                    there && removing.none { it.matches(f.ids) } -> { pushRemove += f; removing += f.ids }
                    there -> deleteLocal += f.key // another copy already carries the remove
                    else -> deleteLocal += f.key
                }
                f.ids.isEmpty -> Unit // can't be matched yet; stays a local favourite
                there -> if (f.pending != null || !f.onTrakt) markSynced += f.key
                // Synced before and gone now: removed on another device.
                f.onTrakt && f.pending == null -> deleteLocal += f.key
                else -> pushAdd += f
            }
        }
        // A title that is being removed here must not be pulled straight back.
        val pull = remote.filterIndexed { i, r -> !claimed[i] && removing.none { it.matches(r.ids) } }
        return Plan(pushAdd, pushRemove, markSynced, deleteLocal, pull)
    }
}
