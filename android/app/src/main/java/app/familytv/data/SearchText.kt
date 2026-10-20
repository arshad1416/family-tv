package app.familytv.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.text.Normalizer

/**
 * Search matching, the same as the Rust app's: every word typed must start a word of the name,
 * ignoring case and accents ("pokemon" finds "Pokémon: The Movie", "star wa" finds "Star Wars").
 * When that finds fewer than asked for, names that merely contain the text fill the rest.
 * Styled letters count as plain ones ([Names.clean]: "raw" finds "ᴿᴬᵂ").
 */
object SearchText {
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    /** More words than this don't narrow a title search any further. */
    private const val MAX_WORDS = 8

    /** Lower case, accents dropped, split on anything that isn't a letter or digit. */
    fun words(text: String): List<String> =
        MARKS.replace(Normalizer.normalize(Names.clean(text), Normalizer.Form.NFD), "").lowercase().split(SEPARATORS).filter { it.isNotEmpty() }

    /** True when every word of [q] starts a word of [name], the rule above, for a list already loaded. */
    fun matches(name: String, q: String): Boolean {
        val want = words(q).take(MAX_WORDS)
        if (want.isEmpty()) return true
        val have = words(name)
        return want.all { w -> have.any { it.startsWith(w) } }
    }

    /** The stored form, a space before every word (" pokemon the movie"), so `LIKE '% poke%'` is a word-prefix match. */
    fun index(name: String): String = words(name).joinToString("") { " $it" }

    /** The word-prefix query and then the contains query for [q], or null when it has no words. */
    internal fun queries(table: String, order: String, q: String, limit: Int): Pair<SupportSQLiteQuery, SupportSQLiteQuery>? {
        val words = words(q).take(MAX_WORDS).ifEmpty { return null }
        val prefix = SimpleSQLiteQuery(
            "SELECT * FROM $table WHERE ${words.joinToString(" AND ") { "searchText LIKE ?" }} ORDER BY $order LIMIT ?",
            (words.map { "% $it%" } + limit).toTypedArray(),
        )
        // Rows from before searchText existed (empty until the next reload) match on the raw name, as before.
        val contains = SimpleSQLiteQuery(
            "SELECT * FROM $table WHERE searchText LIKE ? OR (searchText = '' AND name LIKE ?) ORDER BY $order LIMIT ?",
            arrayOf("%${words.joinToString(" ")}%", "%${q.trim()}%", limit),
        )
        return prefix to contains
    }

    internal suspend fun <T> run(q: String, limit: Int, table: String, order: String, key: (T) -> String, query: suspend (SupportSQLiteQuery) -> List<T>): List<T> {
        val (prefix, contains) = queries(table, order, q, limit) ?: return emptyList()
        val first = query(prefix)
        if (first.size >= limit) return first
        val seen = first.mapTo(HashSet(), key)
        return first + query(contains).filter { key(it) !in seen }.take(limit - first.size)
    }
}

suspend fun CatalogDao.searchChannels(q: String, limit: Int): List<ChannelEntity> =
    SearchText.run(q, limit, "channels", "num, name", { "${it.sourceId}/${it.streamId}" }) { rawChannels(it) }

suspend fun CatalogDao.searchMovies(q: String, limit: Int): List<MovieEntity> =
    SearchText.run(q, limit, "movies", "name", { "${it.sourceId}/${it.streamId}" }) { rawMovies(it) }

suspend fun CatalogDao.searchSeries(q: String, limit: Int): List<SeriesEntity> =
    SearchText.run(q, limit, "series", "name", { "${it.sourceId}/${it.seriesId}" }) { rawSeries(it) }
