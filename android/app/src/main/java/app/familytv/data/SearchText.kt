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
 * finds "Star Wars"). Films and shows: the exact title first ("up" is Up; a leading "The" or "A"
 * and a trailing year don't count), then titles that start with the typed text, then the other
 * word matches, then names that merely contain every word (3 letters or more). Within a group the
 * better known title leads: the provider's rating, a point more when the typed words are whole
 * words of the title (Up in the Air before Upgrade, Breaking Bad still before Break Point). Channels
 * rank Canadian ones first, then English ones, among the good matches, and feeds with a cut-off name
 * ("CBC 22:") last. Styled letters count as plain ones ([Names.clean]).
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
     * Where [indexed] ranks for [q] (0 exact title, 1 starts with the words, 2 has the words, 3
     * starts with the text, 4 every word starts a word), or null when it doesn't match: the SQL
     * ranking, for a list already loaded.
     */
    fun rank(indexed: String, q: String): Int? {
        val want = words(q).take(MAX_WORDS).ifEmpty { return 4 }
        val phrase = want.joinToString("") { " $it" }
        val bare = indexed.replace(TRAILING_YEAR, "")
        return when {
            ARTICLES.any { bare == it + phrase || indexed == it + phrase } -> 0
            ARTICLES.any { indexed.startsWith("$it$phrase ") } -> 1
            want.all { "$indexed ".contains(" $it ") } -> 2
            ARTICLES.any { indexed.startsWith(it + phrase) } -> 3
            want.all { indexed.contains(" $it") } -> 4
            else -> null
        }
    }

    /** [rank]'s groups for films and shows: 0 exact, 1 starts with the text (ranks 1 and 3), 2 the other word matches, 3 contains. */
    fun group(rank: Int): Int = when (rank) { 0 -> 0; 1, 3 -> 1; 2, 4 -> 2; else -> 3 }

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

    /**
     * The ranked query for [q], or null when it has no words. [order] is the ORDER BY, where
     * `{tier}` stands for [rank]'s number (5 for names that only contain the words).
     */
    internal fun query(table: String, order: String, q: String, limit: Int): SupportSQLiteQuery? {
        val words = words(q).take(MAX_WORDS).ifEmpty { return null }
        val phrase = words.joinToString("") { " $it" }
        val prefix = words.joinToString(" AND ") { "searchText LIKE ?" }
        val prefixArgs = words.map { "% $it%" }
        val contains = words.all { it.length >= CONTAINS_MIN }
        val tier = buildString {
            append("(CASE WHEN ${ARTICLES.joinToString(" OR ") { "searchText = ? OR searchText GLOB ?" }} THEN 0")
            append(" WHEN ${ARTICLES.joinToString(" OR ") { "searchText LIKE ?" }} THEN 1")
            append(" WHEN ${words.joinToString(" AND ") { "searchText || ' ' LIKE ?" }} THEN 2")
            append(" WHEN ${ARTICLES.joinToString(" OR ") { "searchText LIKE ?" }} THEN 3")
            append(" WHEN $prefix THEN 4 ELSE 5 END)")
        }
        val tierArgs = buildList<Any> {
            ARTICLES.forEach { add(it + phrase); add("$it$phrase [12][0-9][0-9][0-9]") }
            ARTICLES.forEach { add("$it$phrase %") }
            words.forEach { add("% $it %") }
            ARTICLES.forEach { add("$it$phrase%") }
            addAll(prefixArgs)
        }
        val parts = order.split("{tier}")
        val sql = buildString {
            append("SELECT * FROM $table WHERE ($prefix)")
            if (contains) append(" OR (${words.joinToString(" AND ") { "searchText LIKE ?" }})")
            // Rows from before searchText existed (empty until the next reload) match on the raw name, as before.
            append(" OR (searchText = '' AND name LIKE ?)")
            append(" ORDER BY ").append(parts.joinToString(tier)).append(" LIMIT ?")
        }
        val args = buildList<Any> {
            addAll(prefixArgs)
            if (contains) addAll(words.map { "%$it%" })
            add("%${q.trim()}%")
            repeat(parts.size - 1) { addAll(tierArgs) }
            add(limit)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    internal suspend fun <T> run(q: String, limit: Int, table: String, order: String, query: suspend (SupportSQLiteQuery) -> List<T>): List<T> =
        query(table, order, q, limit)?.let { query(it) }.orEmpty()

    /**
     * Films and shows: by [group]; within one, the provider's rating with a point more for whole
     * words (ranks 1 and 2), then rank, the shorter title and the name.
     */
    internal const val TITLE_ORDER = "(CASE WHEN {tier} = 0 THEN 0 WHEN {tier} IN (1, 3) THEN 1 WHEN {tier} IN (2, 4) THEN 2 ELSE 3 END), " +
        "COALESCE(CAST(rating AS REAL), 0) + (CASE WHEN {tier} IN (1, 2) THEN 1.0 ELSE 0 END) DESC, {tier}, length(searchText), name"

    private const val CATEGORY = "(SELECT c.name FROM categories c WHERE c.sourceId = channels.sourceId AND c.kind = 'live' AND c.categoryId = channels.categoryId)"
    private fun canadian(x: String) =
        "upper(ltrim($x)) GLOB 'CA[^A-Z]*' OR upper(ltrim($x)) GLOB 'CAN[^A-Z]*' OR upper(ltrim($x)) GLOB '[[(]CA[])]*' OR $x LIKE '%canad%'"
    private fun english(x: String) =
        listOf("US", "USA", "UK", "GB", "EN").joinToString(" OR ") { "upper(ltrim($x)) GLOB '$it[^A-Z]*' OR upper(ltrim($x)) GLOB '[[(]$it[])]*'" } +
            " OR $x LIKE '%english%'"

    /** Canadian broadcasters, as words of a channel's search text: they lead even where a Canadian line also carries US channels. */
    private val CANADIAN_BRANDS = listOf(
        "cbc", "ctv", "global", "citytv", "cp24", "tsn", "sportsnet", "rds", "tva", "cpac", "aptn", "tvo", "omni", "chch", "noovo", "ytv", "treehouse", "crave",
    )
    /** A country code other than Canada's in front ("EG| CBC Drama", "[AR] CBC"): Egypt's CBC isn't Canada's. */
    private fun foreign(x: String) =
        "((upper(ltrim($x)) GLOB '[A-Z][A-Z][|:]*' OR upper(ltrim($x)) GLOB '[A-Z][A-Z][A-Z][|:]*' OR upper(ltrim($x)) GLOB '[[(][A-Z][A-Z][])]*') " +
            "AND NOT (${canadian(x)}) AND upper(ltrim($x)) NOT GLOB 'EN[^A-Z]*' AND upper(ltrim($x)) NOT GLOB 'FR[^A-Z]*')"
    private val canadianBrand = "((${CANADIAN_BRANDS.joinToString(" OR ") { "searchText || ' ' LIKE '% $it %'" }}) AND NOT ${foreign("name")} AND NOT ${foreign("COALESCE($CATEGORY, '')")})"
    /** The provider's own guide id names Canada ("CBCNewsNetwork.ca"); an extra guide's id ("x:...") doesn't count. */
    private const val CANADIAN_ID = "(epgId NOT LIKE 'x:%' AND (lower(epgId) GLOB '*.ca' OR lower(epgId) GLOB '*.ca[0-9]'))"

    /**
     * Channels: cut-off names ("CBC 22:") last; then the good matches (rank 0 to 2), word starts,
     * contains; within each, Canadian broadcasters (by name, or the provider's guide id), then the
     * rest of Canadian lines (name or category coded CA, which can be US channels), then English
     * ones; then rank, the shorter name and the channel number.
     */
    internal val CHANNEL_ORDER =
        "(CASE WHEN rtrim(name) GLOB '*[:|-]' THEN 1 ELSE 0 END), (CASE WHEN {tier} <= 2 THEN 0 WHEN {tier} <= 4 THEN 1 ELSE 2 END), " +
            "(CASE WHEN $canadianBrand OR $CANADIAN_ID THEN 0 WHEN ${canadian("name")} OR ${canadian(CATEGORY)} THEN 1 " +
            "WHEN ${english("name")} OR ${english(CATEGORY)} THEN 2 ELSE 3 END), " +
            "{tier}, length(searchText), num, name"
}

suspend fun CatalogDao.searchChannels(q: String, limit: Int): List<ChannelEntity> =
    SearchText.run(q, limit, "channels", SearchText.CHANNEL_ORDER) { rawChannels(it) }

suspend fun CatalogDao.searchMovies(q: String, limit: Int): List<MovieEntity> =
    SearchText.run(q, limit, "movies", SearchText.TITLE_ORDER) { rawMovies(it) }

suspend fun CatalogDao.searchSeries(q: String, limit: Int): List<SeriesEntity> =
    SearchText.run(q, limit, "series", SearchText.TITLE_ORDER) { rawSeries(it) }
