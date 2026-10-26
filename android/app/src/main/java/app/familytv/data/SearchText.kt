package app.familytv.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.familytv.iptv.SeriesTitles
import java.text.Normalizer

/**
 * Search matching and ranking, the same as the Rust app's. Titles are matched on their clean
 * display title ([SeriesTitles.parse]: no "EN -", "[4K]" or "(2019)" tags) plus the year, channels
 * on their listed name ([Names.channel]: no "CA|" code), ignoring case, accents and "&" vs "and".
 * Every word typed must start a word of the name ("pokemon" finds "Pokémon: The Movie", "star wa"
 * finds "Star Wars"). Results rank exact title first ("up" is Up before Up in the Air; a leading
 * "The" or "A" and a trailing year don't count), then titles that start with the typed text, then
 * the rest of the word matches; ties go to the provider's higher rating, then the shorter title.
 * When that finds fewer than asked for, names that merely contain every word (3 letters or more)
 * fill the rest. Styled letters count as plain ones ([Names.clean]: "raw" finds "ᴿᴬᵂ").
 */
object SearchText {
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val YEAR = Regex("""(?<!\d)(19|20)\d\d(?!\d)""")
    private val ARTICLES = listOf("", " the", " a", " an")
    /** More words than this don't narrow a title search any further. */
    private const val MAX_WORDS = 8
    /** Contains-matching only for words this long: "up" inside "Superman" is noise. */
    private const val CONTAINS_MIN = 3

    /** Lower case, accents dropped, "&" (and "&amp;") as "and", split on anything that isn't a letter or digit. */
    fun words(text: String): List<String> =
        MARKS.replace(Normalizer.normalize(Names.clean(text).replace("&amp;", "&").replace("&", " and "), Normalizer.Form.NFD), "")
            .lowercase().split(SEPARATORS).filter { it.isNotEmpty() }

    /** True when every word of [q] starts a word of [name], the rule above, for a list already loaded. */
    fun matches(name: String, q: String): Boolean = rank(index(name), q) != null

    /**
     * Where [name] ranks for [q] (0 exact title, 1 starts with it, 2 every word starts a word), or
     * null when it doesn't match: the SQL ranking, for a list already loaded.
     */
    fun rank(indexed: String, q: String): Int? {
        val want = words(q).take(MAX_WORDS).ifEmpty { return 2 }
        val phrase = want.joinToString("") { " $it" }
        val bare = indexed.replace(TRAILING_YEAR, "")
        return when {
            ARTICLES.any { bare == it + phrase || indexed == it + phrase } -> 0
            ARTICLES.any { indexed.startsWith(it + phrase) } -> 1
            want.all { indexed.contains(" $it") } -> 2
            else -> null
        }
    }

    private val TRAILING_YEAR = Regex(" (19|20)\\d\\d$")

    /** The stored form, a space before every word (" pokemon the movie"), so `LIKE '% poke%'` is a word-prefix match. */
    fun index(name: String): String = words(name).joinToString("") { " $it" }

    /** A film's or show's stored form: its clean title, then its year (from the list, else from the raw name). */
    fun titleIndex(name: String, year: Int?): String {
        val y = year?.takeIf { it in 1900..2099 }?.toString() ?: YEAR.findAll(name).lastOrNull()?.value
        return index(SeriesTitles.parse(name).title) + (y?.let { " $it" } ?: "")
    }

    /** A channel's stored form: its name as listed, without the country code. */
    fun channelIndex(name: String): String = index(Names.channel(name))

    /** The ranked query for [q], or null when it has no words. [tiebreak] orders rows of one rank. */
    internal fun query(table: String, tiebreak: String, q: String, limit: Int): SupportSQLiteQuery? {
        val words = words(q).take(MAX_WORDS).ifEmpty { return null }
        val phrase = words.joinToString("") { " $it" }
        val prefix = words.joinToString(" AND ") { "searchText LIKE ?" }
        val prefixArgs = words.map { "% $it%" }
        val contains = words.all { it.length >= CONTAINS_MIN }
        val sql = buildString {
            append("SELECT * FROM $table WHERE ($prefix)")
            if (contains) append(" OR (${words.joinToString(" AND ") { "searchText LIKE ?" }})")
            // Rows from before searchText existed (empty until the next reload) match on the raw name, as before.
            append(" OR (searchText = '' AND name LIKE ?)")
            append(" ORDER BY CASE")
            append(" WHEN ${ARTICLES.joinToString(" OR ") { "searchText = ? OR searchText GLOB ?" }} THEN 0")
            append(" WHEN ${ARTICLES.joinToString(" OR ") { "searchText LIKE ?" }} THEN 1")
            append(" WHEN $prefix THEN 2 ELSE 3 END, $tiebreak LIMIT ?")
        }
        val args = buildList<Any> {
            addAll(prefixArgs)
            if (contains) addAll(words.map { "%$it%" })
            add("%${q.trim()}%")
            ARTICLES.forEach { add(it + phrase); add("$it$phrase [12][0-9][0-9][0-9]") }
            ARTICLES.forEach { add("$it$phrase%") }
            addAll(prefixArgs)
            add(limit)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    internal suspend fun <T> run(q: String, limit: Int, table: String, tiebreak: String, query: suspend (SupportSQLiteQuery) -> List<T>): List<T> =
        query(table, tiebreak, q, limit)?.let { query(it) }.orEmpty()

    /** Rows of one rank: the provider's higher rating first, then the shorter title. */
    internal const val TITLE_ORDER = "CAST(rating AS REAL) DESC, length(searchText), name"
}

suspend fun CatalogDao.searchChannels(q: String, limit: Int): List<ChannelEntity> =
    SearchText.run(q, limit, "channels", "length(searchText), num, name") { rawChannels(it) }

suspend fun CatalogDao.searchMovies(q: String, limit: Int): List<MovieEntity> =
    SearchText.run(q, limit, "movies", SearchText.TITLE_ORDER) { rawMovies(it) }

suspend fun CatalogDao.searchSeries(q: String, limit: Int): List<SeriesEntity> =
    SearchText.run(q, limit, "series", SearchText.TITLE_ORDER) { rawSeries(it) }
