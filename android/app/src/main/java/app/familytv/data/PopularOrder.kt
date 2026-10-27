package app.familytv.data

import app.familytv.iptv.SeriesTitles
import app.familytv.trailer.TmdbHit
import kotlin.math.abs

/**
 * Search results in order of how well known the titles are, by TMDB's own search for the same
 * words: the line's copies can't tell Up in the Air from Upgraded, nor Breaking Bad from Break
 * Clause. Exact titles stay first; then the titles TMDB found (whole words or not), in TMDB's
 * order; then the rest as [SearchText] ordered them; titles that only contain the text last.
 */
object PopularOrder {
    /** How many of TMDB's results count: its first page. */
    const val TOP = 20

    fun <T> apply(items: List<T>, q: String, hits: List<TmdbHit>, title: (T) -> String, year: (T) -> Int?, ids: (T) -> Collection<String>): List<T> {
        if (hits.isEmpty() || items.size < 2) return items
        val top = hits.take(TOP).map { Triple(it.id, key(it.title), it.year) }
        fun place(x: T): Int? {
            val own = ids(x)
            val k = key(title(x))
            val y = year(x)
            return top.indexOfFirst { (id, hk, hy) -> id in own || (hk == k && (y == null || hy == null || abs(y - hy) <= 1)) }.takeIf { it >= 0 }
        }
        return items.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { (_, x) ->
                // No rank: the title only contains the text ("erman" in Superman).
                when (SearchText.rank(SearchText.index(title(x)), q)?.let(SearchText::group)) {
                    0 -> 0
                    null, 3 -> 3
                    else -> if (place(x) != null) 1 else 2
                }
            }.thenBy { (_, x) -> place(x) ?: Int.MAX_VALUE }.thenBy { it.index },
        ).map { it.value }
    }

    /** A title's key without a leading article: "The Upside" is "upside" on either side. */
    private fun key(title: String): String = SeriesTitles.key(title).removePrefix("the ").removePrefix("a ")
}
