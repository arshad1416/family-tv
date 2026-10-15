package app.familytv.trakt

import java.text.Normalizer

/**
 * Matching provider titles ("EN - The Matrix (1999) [4K]") with Trakt titles ("The Matrix", 1999)
 * when there is no TMDB id to go on. Pure functions, unit tested.
 */
object TitleMatch {
    data class Parsed(val title: String, val year: Int?)

    // "EN - ", "|EN| ", "[FR] ", "NF: " at the start: short upper-case tags followed by a separator.
    // A dash only counts with a space next to it, so "WALL-E" keeps its name.
    private val LEADING_TAGS = Regex("^(?:\\s*(?:[\\[|(]\\s*[A-Z0-9+]{2,5}\\s*[\\]|)]|[A-Z0-9+]{2,5}(?:\\s*[|:]|\\s+-|-\\s)))+")
    private val BRACKETED = Regex("[\\[(][^\\])]*[\\])]")
    private val QUALITY = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hdr|hevc|x264|x265|1080p|720p|2160p|multi(?:-?sub)?|vostfr|dubbed|subbed)\\b")
    private val YEAR_IN_PARENS = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private const val SEPARATORS = "-|:"
    private val TRAILING_YEAR = Regex("(?:\\s|-)((?:19|20)\\d{2})\\s*$")

    /** Splits a provider name into a clean title and its year, if the name carries one. */
    fun parse(raw: String): Parsed {
        var s = raw.trim()
        var year: Int? = YEAR_IN_PARENS.findAll(s).lastOrNull()?.groupValues?.get(1)?.toInt()
        s = LEADING_TAGS.replace(s, "")
        s = BRACKETED.replace(s, " ")
        s = QUALITY.replace(s, " ")
        s = s.replace(Regex("\\s+"), " ").trim { it.isWhitespace() || it in SEPARATORS }
        if (year == null) {
            TRAILING_YEAR.find(s)?.let { m ->
                // Keep titles that are only a year ("1917", "2012").
                val rest = s.substring(0, m.range.first).trim { it.isWhitespace() || it in SEPARATORS }
                if (rest.isNotEmpty()) { year = m.groupValues[1].toInt(); s = rest }
            }
        }
        return Parsed(s, year)
    }

    /** Comparison key: lower case, no accents or punctuation, "&" as "and", no leading "the". */
    fun key(title: String): String {
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removePrefix("the ")
            .replace(" ", "")
    }

    /** Same title, and the same year give or take one (release dates differ by country) when both are known. */
    fun same(aTitle: String, aYear: Int?, bTitle: String, bYear: Int?): Boolean {
        val ka = key(aTitle)
        if (ka.isEmpty() || ka != key(bTitle)) return false
        return aYear == null || bYear == null || kotlin.math.abs(aYear - bYear) <= 1
    }

    data class Candidate<T>(val title: String, val year: Int?, val value: T)

    /**
     * Picks the Trakt search hit for a provider item. Only a title match counts; among those an
     * exact year wins, then a near year, then (when either year is unknown) the first hit,
     * which is Trakt's most relevant.
     */
    fun <T> best(name: String, year: Int?, hits: List<Candidate<T>>): T? {
        val p = parse(name)
        val y = year ?: p.year
        val matching = hits.filter { same(p.title, y, it.title, it.year) }
        if (matching.isEmpty()) return null
        return (matching.firstOrNull { y != null && it.year == y } ?: matching.first()).value
    }

    /** Catalog rows that are the same title as a Trakt item. */
    fun <T> catalogMatches(title: String, year: Int?, rows: List<Candidate<T>>): List<T> =
        rows.filter { r -> parse(r.title).let { p -> same(p.title, r.year ?: p.year, title, year) } }.map { it.value }
}
