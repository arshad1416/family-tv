package app.familytv.data

import app.familytv.iptv.SeriesTitles
import app.familytv.trailer.TmdbVotes
import kotlin.math.abs

/**
 * How well known films (or shows) are: TMDB's most-voted titles, looked up by TMDB id, else by title
 * without a leading "The" or "A" and the year give or take one (Dune 2021 is not Dune 1984).
 */
class Popularity(titles: List<TmdbVotes>) {
    private val byId = titles.associate { it.id to it.votes }
    private val byKey = titles.groupBy { key(it.title) }

    val size get() = byId.size

    /** The votes for the title with TMDB [ids], or called [title] from [year]; null when it isn't on the list. */
    fun votes(ids: Collection<String>, title: String, year: Int?): Int? {
        ids.firstNotNullOfOrNull { byId[it] }?.let { return it }
        return byKey[key(title)]?.filter { year == null || it.year == null || abs(year - it.year) <= 1 }?.maxOfOrNull { it.votes }
    }

    private companion object {
        fun key(title: String): String = SeriesTitles.key(title).removePrefix("the ").removePrefix("a ")
    }
}

/**
 * Search results in order of how well known the titles are, by TMDB's votes ([Popularity]): the
 * line's copies can't tell Up in the Air from Upgraded, nor Breaking Bad from Break Clause, and
 * TMDB's own search answers whole words only ("break" never finds Breaking Bad). Exact titles
 * first; then the titles whose words start with the typed ones, by votes weighted for how well
 * they match: three times for a title that starts with the typed words (Up in the Air), twice for
 * the words later in it (Don't Look Up, Prison Break), once for a title that only starts with the
 * text (Upgrade, Breaking Bad, which still leads "break" on its votes); then those off the list,
 * as [SearchText] ordered them (copies, then rating); then the words inside other words (Once Upon
 * a Time for "up"), then titles that only contain the text, each by votes, then the rest of them.
 */
object PopularOrder {
    fun <T> apply(items: List<T>, q: String, list: Popularity?, title: (T) -> String, year: (T) -> Int?, ids: (T) -> Collection<String>): List<T> {
        if (list == null || list.size == 0 || items.size < 2) return items
        return items.withIndex().map { (i, x) ->
            val rank = SearchText.rank(SearchText.index(title(x)), q)
            val votes = list.votes(ids(x), title(x), year(x))
            val tier = when {
                rank == 0 -> 0
                rank in 1..3 -> if (votes != null) 1 else 2
                rank == 4 -> 3
                // No rank: the title only contains the text ("erman" in Superman).
                else -> 4
            }
            val weight = when (rank) { 1 -> 3L; 2 -> 2L; else -> 1L }
            Triple(tier, votes?.let { it * weight } ?: -1L, i) to x
        }.sortedWith(compareBy<Pair<Triple<Int, Long, Int>, T>> { it.first.first }.thenByDescending { it.first.second }.thenBy { it.first.third }).map { it.second }
    }
}
