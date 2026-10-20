package app.familytv.iptv

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * Providers list one show several times, once per language ("EN - Show", "FR - Show",
 * "Show (ES)", or the same title in "EN | Series" and "AR | Series"). These pure functions
 * clean such titles, tell which language each copy is in, and group the copies into one show.
 * Unit tested in SeriesTitlesTest.
 */
object SeriesTitles {
    /** Language code for a copy whose title and category name no language. */
    const val ORIGINAL = "orig"

    data class Parsed(
        /** The title with language, quality and platform tags removed, for display. */
        val title: String,
        /** Grouping key: [title] casefolded, without accents or punctuation. */
        val key: String,
        /** Language named in the title, or null. */
        val lang: String?,
        /** Quality and platform tags found ("4K", "NF"), to tell two copies in one language apart. */
        val tags: List<String>,
    )

    // ---- languages ----

    private val NAMES = linkedMapOf(
        "en" to "English", "fr" to "French", "es" to "Spanish", "es-419" to "Spanish (Latin America)",
        "ar" to "Arabic", "de" to "German", "it" to "Italian", "pt" to "Portuguese", "pt-br" to "Portuguese (Brazil)",
        "tr" to "Turkish", "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "hi" to "Hindi", "ur" to "Urdu",
        "fa" to "Persian", "ku" to "Kurdish", "sq" to "Albanian", "ro" to "Romanian", "el" to "Greek",
        "sv" to "Swedish", "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "he" to "Hebrew",
        "ko" to "Korean", "ja" to "Japanese", "zh" to "Chinese", "ta" to "Tamil", "te" to "Telugu",
        "ml" to "Malayalam", "bn" to "Bengali", "pa" to "Punjabi",
    )

    /** Short codes (2-3 letters): only trusted in upper case, since "It" or "No" may be words. */
    private val CODES = mapOf(
        "EN" to "en", "ENG" to "en",
        "FR" to "fr", "FRA" to "fr", "FRE" to "fr", "VF" to "fr", "VFF" to "fr", "VFQ" to "fr",
        "ES" to "es", "ESP" to "es", "SPA" to "es", "LAT" to "es-419",
        "AR" to "ar", "ARA" to "ar",
        "DE" to "de", "GER" to "de", "DEU" to "de",
        "IT" to "it", "ITA" to "it",
        "PT" to "pt", "POR" to "pt", "BR" to "pt-br",
        "TR" to "tr", "TUR" to "tr",
        "NL" to "nl", "DUT" to "nl",
        "PL" to "pl", "POL" to "pl",
        "RU" to "ru", "RUS" to "ru",
        "HI" to "hi", "HIN" to "hi",
        "UR" to "ur", "URD" to "ur",
        "FA" to "fa", "PER" to "fa", "IR" to "fa",
        "KU" to "ku",
        "AL" to "sq", "ALB" to "sq", "SQ" to "sq",
        "RO" to "ro", "ROM" to "ro",
        "GR" to "el", "GRE" to "el", "EL" to "el",
        "SE" to "sv", "SWE" to "sv", "SV" to "sv",
        "NO" to "no", "NOR" to "no",
        "DK" to "da", "DAN" to "da", "DA" to "da",
        "FI" to "fi", "FIN" to "fi",
        "HE" to "he", "HEB" to "he", "IL" to "he",
        "KR" to "ko", "KOR" to "ko", "KO" to "ko",
        "JP" to "ja", "JPN" to "ja", "JA" to "ja",
        "CN" to "zh", "CHI" to "zh", "ZH" to "zh",
        "TA" to "ta", "TAM" to "ta", "TE" to "te", "TEL" to "te",
        "ML" to "ml", "MAL" to "ml", "BN" to "bn", "BEN" to "bn", "PA" to "pa", "PUN" to "pa",
    )

    /** Language names, in any case. */
    private val WORDS = mapOf(
        "ENGLISH" to "en", "FRENCH" to "fr", "FRANCAIS" to "fr", "FRANÇAIS" to "fr",
        "SPANISH" to "es", "ESPANOL" to "es", "ESPAÑOL" to "es", "CASTELLANO" to "es", "LATINO" to "es-419",
        "ARABIC" to "ar", "ARAB" to "ar", "ARABIA" to "ar", "GERMAN" to "de", "DEUTSCH" to "de",
        "ITALIAN" to "it", "ITALIANO" to "it", "PORTUGUESE" to "pt", "BRAZIL" to "pt-br", "BRASIL" to "pt-br",
        "TURKISH" to "tr", "TURK" to "tr", "DUTCH" to "nl", "NEDERLANDS" to "nl", "POLISH" to "pl", "POLSKI" to "pl",
        "RUSSIAN" to "ru", "HINDI" to "hi", "URDU" to "ur", "PERSIAN" to "fa", "FARSI" to "fa",
        "KURDISH" to "ku", "KURD" to "ku", "ALBANIAN" to "sq", "ROMANIAN" to "ro", "GREEK" to "el",
        "SWEDISH" to "sv", "NORWEGIAN" to "no", "DANISH" to "da", "FINNISH" to "fi", "HEBREW" to "he",
        "KOREAN" to "ko", "JAPANESE" to "ja", "CHINESE" to "zh", "TAMIL" to "ta", "TELUGU" to "te",
        "MALAYALAM" to "ml", "BENGALI" to "bn", "BANGLA" to "bn", "PUNJABI" to "pa",
    )

    /** Country prefixes some providers use for categories ("UK | Series"); not trusted in titles. */
    private val COUNTRIES = mapOf("UK" to "en", "US" to "en", "USA" to "en", "GB" to "en", "AU" to "en")

    private val QUALITY = setOf(
        "4K", "8K", "UHD", "FHD", "HD", "SD", "HQ", "LQ", "HEVC", "H265", "H264", "X265", "HDR", "HDR10", "DV",
        "1080P", "720P", "2160P", "480P", "60FPS", "MULTI", "MULTISUB", "MULTI-SUB", "SUB", "SUBS", "SUBBED",
        "DUB", "DUBBED", "VOST", "VOSTFR", "VO", "3D",
    )

    private val PLATFORMS = setOf(
        "NF", "NETFLIX", "AMZ", "AMAZON", "PRIME", "HBO", "HBOMAX", "DSNP", "DISNEY", "DISNEY+", "ATVP", "APPLETV",
        "APPLETV+", "HULU", "PCOK", "PEACOCK", "PMTP", "PARAMOUNT", "PARAMOUNT+", "CRAVE", "STARZ", "SHOWTIME",
    )

    /**
     * Shop-window words some providers add to a title ("4K-TOP - Dune", "TOP - The Mandalorian").
     * Dropped beside a quality, platform or language tag, or alone when written in capitals and
     * cut off by a separator, so "Top Gun", "New Girl" and "Top - Gun" keep their names.
     */
    private val FILLERS = setOf("TOP", "NEW", "VIP", "BEST", "HOT", "PREMIUM", "PREM", "PLUS", "ONLY", "EXCLUSIVE")

    /** Region shelves some providers put in front of a title ("AS - Fighting Mulan"); dropped like [FILLERS]. */
    private val REGIONS = setOf("AS", "ASIA", "ASIAN", "EU", "EUR", "AF", "AFR", "AFRICA", "EXYU", "MENA", "LATAM")

    fun languageName(code: String): String = if (code == ORIGINAL) "Original" else NAMES[code] ?: code.uppercase(Locale.ROOT)

    private fun langOf(token: String): String? {
        val up = token.uppercase(Locale.ROOT)
        WORDS[up]?.let { return it }
        return if (token == up) CODES[up] else null
    }

    /** Language named by a category such as "EN | Series", "FRENCH SERIES", "|AR| مسلسلات" or "SERIES - ENG". */
    fun fromCategory(name: String?): String? {
        val tokens = name?.split(TOKEN_SPLIT)?.filter { it.isNotEmpty() }.orEmpty()
        for (t in tokens) WORDS[t.uppercase(Locale.ROOT)]?.let { return it }
        // Short codes only at the start or end, where providers put them.
        for (t in listOfNotNull(tokens.firstOrNull(), tokens.lastOrNull())) {
            if (t != t.uppercase(Locale.ROOT)) continue
            (CODES[t] ?: COUNTRIES[t])?.let { return it }
        }
        return null
    }

    /** The copy's language: from its title, else its category, else [ORIGINAL]. */
    fun detect(title: String, category: String?): String = parse(title).lang ?: fromCategory(category) ?: ORIGINAL

    // ---- titles ----

    private const val SEP = "[-:|▎•►»–—]"
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}+]+")
    private val BRACKET_PREFIX = Regex("^[\\[(|]\\s*([^\\[\\]()|]{1,24}?)\\s*[\\])|]\\s*(?:$SEP+\\s*)?")
    private val PLAIN_PREFIX = Regex("^([\\p{L}\\p{N}+]{2,12}(?:[-/][\\p{L}\\p{N}+]{2,12})?)\\s*$SEP+\\s*")
    private val BRACKET_SUFFIX = Regex("\\s*[\\[(]\\s*([^\\[\\]()]{1,24}?)\\s*[\\])]\\s*$")
    private val PLAIN_SUFFIX = Regex("\\s+$SEP+\\s*([\\p{L}\\p{N}+]{2,12})\\s*$")
    private val BARE_SUFFIX = Regex("\\s+([\\p{L}\\p{N}+]{2,12})$")
    private val YEAR = Regex("(19|20)\\d{2}")
    private val EDGE = Regex("^[\\s\\-:|▎•►»–—.,]+|[\\s\\-:|▎•►»–—.,]+$")
    private val TAG_SPLIT = Regex("[\\s\\-/,]+")
    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** What a tag (prefix, suffix or bracket contents) holds, or null when it is part of the title. */
    private class Tag(val lang: String?, val tags: List<String>)

    private fun tag(text: String, allowYear: Boolean, allowLang: Boolean = true, separated: Boolean = true): Tag? {
        val tokens = text.split(TAG_SPLIT).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var lang: String? = null
        val tags = ArrayList<String>()
        var fillers = false
        var shouted = true
        for (t in tokens) {
            val up = t.uppercase(Locale.ROOT)
            val l = if (allowLang) langOf(t) else null
            when {
                l != null -> if (lang == null) lang = l
                up in QUALITY || up in PLATFORMS -> tags += up
                allowYear && isYear(t) -> Unit
                up in FILLERS || up in REGIONS -> { fillers = true; if (t != up) shouted = false }
                else -> return null
            }
        }
        if (fillers && lang == null && tags.isEmpty() && !(separated && shouted)) return null
        return Tag(lang, tags)
    }

    private fun isYear(t: String): Boolean =
        YEAR.matches(t) && t.toInt() <= Calendar.getInstance().get(Calendar.YEAR) + 1

    /**
     * Names already parsed: every poster on a shelf parses its copies' names for its title, badge
     * and language as it scrolls in, so a name is parsed once instead of on each pass (TV re-test:
     * scrolling the home was bound by main-thread work).
     */
    private val parsed = object : LinkedHashMap<String, Parsed>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>) = size > PARSED_MAX
    }
    private const val PARSED_MAX = 4096

    fun parse(raw: String): Parsed {
        synchronized(parsed) { parsed[raw] }?.let { return it }
        return parseNow(raw).also { synchronized(parsed) { parsed[raw] = it } }
    }

    private fun parseNow(raw: String): Parsed {
        var s = raw.trim()
        var lang: String? = null
        val tags = ArrayList<String>()
        fun take(t: Tag) { if (lang == null) lang = t.lang; tags += t.tags }

        // Leading tags: "EN - ", "|AR| ", "[4K] ", "NF: ".
        repeat(4) {
            val m = BRACKET_PREFIX.find(s) ?: PLAIN_PREFIX.find(s) ?: return@repeat
            val t = tag(m.groupValues[1], allowYear = false) ?: return@repeat
            val rest = s.substring(m.range.last + 1)
            if (rest.isBlank()) return@repeat
            take(t); s = rest
        }
        // Trailing tags: " (ES)", " [4K]", " - FR", " (2019)", " FHD".
        repeat(4) {
            val bracket = BRACKET_SUFFIX.find(s)
            val (m, t) = when {
                bracket != null -> bracket to tag(bracket.groupValues[1], allowYear = true)
                else -> PLAIN_SUFFIX.find(s)?.let { it to tag(it.groupValues[1], allowYear = true) }
                    ?: BARE_SUFFIX.find(s)?.let { it to tag(it.groupValues[1], allowYear = true, allowLang = false, separated = false) }
                    ?: return@repeat
            }
            if (t == null) return@repeat
            val rest = s.substring(0, m.range.first)
            if (rest.isBlank()) return@repeat
            take(t); s = rest
        }
        val title = s.replace(EDGE, "").ifBlank { raw.trim() }
        return Parsed(title, key(title), lang, tags)
    }

    /**
     * The picture-quality badge for a title's copies ("4K", "HDR" or "4K HDR"), from the tags in
     * their [raw] names; null when none says 4K or HDR.
     */
    fun qualityBadge(raw: List<String>): String? {
        val tags = raw.flatMap { parse(it).tags }.toSet()
        return listOfNotNull(
            "4K".takeIf { tags.any { it in UHD } },
            "HDR".takeIf { tags.any { it in HDR } },
        ).joinToString(" ").ifEmpty { null }
    }

    private val UHD = setOf("4K", "8K", "UHD", "2160P")
    private val HDR = setOf("HDR", "HDR10", "DV")

    /** Casefolded, accent-free, punctuation-free form of a title, for grouping. */
    fun key(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(NOT_WORD, " ")
            .trim()

    // ---- grouping ----

    /** Two copies are one show: same TMDB id, or (when either lacks one) the same title key. */
    fun sameShow(tmdbA: String?, keyA: String, tmdbB: String?, keyB: String): Boolean =
        if (tmdbA != null && tmdbB != null) tmdbA == tmdbB else keyA.isNotEmpty() && keyA == keyB

    /**
     * Groups copies of one show, keeping list order (a group sits where its first copy was).
     * Key: "tmdb:<id>" when a copy has a TMDB id, else "title:<key>". A copy without an id joins
     * the TMDB group whose copies share its title, but only when exactly one such group exists
     * (two shows called "The Office" with different ids stay apart).
     */
    fun <T> group(items: List<T>, tmdb: (T) -> String?, key: (T) -> String): List<Pair<String, List<T>>> {
        val tmdbByTitle = HashMap<String, MutableSet<String>>()
        for (it in items) { val id = tmdb(it) ?: continue; val k = key(it); if (k.isNotEmpty()) tmdbByTitle.getOrPut(k) { HashSet() } += id }
        val out = LinkedHashMap<String, MutableList<T>>()
        items.forEachIndexed { i, it ->
            val id = tmdb(it)
            val k = key(it)
            val g = when {
                id != null -> "tmdb:$id"
                k.isEmpty() -> "item:$i"
                else -> tmdbByTitle[k]?.singleOrNull()?.let { only -> "tmdb:$only" } ?: "title:$k"
            }
            out.getOrPut(g) { ArrayList() } += it
        }
        return out.map { (k, v) -> k to v }
    }

    // ---- choosing a copy ----

    /**
     * Which copy to open: the first of [preferred] (this show's last choice, the last choice on
     * any show, the device language) that a copy has, matching "es" to "es-419" when no exact
     * copy exists; then an [ORIGINAL] copy; then the first.
     */
    fun chooseVariant(langs: List<String>, preferred: List<String?>): Int {
        if (langs.isEmpty()) return -1
        for (p in preferred) {
            if (p.isNullOrBlank()) continue
            val want = p.lowercase(Locale.ROOT)
            langs.indexOf(want).takeIf { it >= 0 }?.let { return it }
            val base = want.substringBefore('-')
            langs.indexOfFirst { it.substringBefore('-') == base }.takeIf { it >= 0 }?.let { return it }
        }
        return langs.indexOf(ORIGINAL).takeIf { it >= 0 } ?: 0
    }

    /** Picker labels: the language, plus the copy's tags (or a number) when two copies share it. */
    fun variantLabels(variants: List<Pair<String, List<String>>>): List<String> {
        val count = variants.groupingBy { it.first }.eachCount()
        val seen = HashMap<String, Int>()
        return variants.map { (lang, tags) ->
            val name = languageName(lang)
            val n = seen.merge(lang, 1, Int::plus)!!
            when {
                (count[lang] ?: 0) < 2 -> name
                tags.isNotEmpty() -> "$name · ${tags.joinToString(" ")}"
                else -> "$name · $n"
            }
        }
    }
}
