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
 * first; then every title whose words start with the typed ones, the most voted first; then the
 * titles that only contain the text. Titles off the list follow the listed ones of their group, as
 * [SearchText] ordered them (copies, then rating).
 */
object PopularOrder {
    fun <T> apply(items: List<T>, q: String, list: Popularity?, title: (T) -> String, year: (T) -> Int?, ids: (T) -> Collection<String>): List<T> {
        if (list == null || list.size == 0 || items.size < 2) return items
        return items.withIndex().map { (i, x) ->
            val group = when (SearchText.rank(SearchText.index(title(x)), q)) {
                0 -> 0
                // No rank: the title only contains the text ("erman" in Superman).
                null -> 2
                else -> 1
            }
            Triple(group, list.votes(ids(x), title(x), year(x)) ?: -1, i) to x
        }.sortedWith(compareBy<Pair<Triple<Int, Int, Int>, T>> { it.first.first }.thenByDescending { it.first.second }.thenBy { it.first.third }).map { it.second }
    }
}
