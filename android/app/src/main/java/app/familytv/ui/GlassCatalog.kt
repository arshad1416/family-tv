package app.familytv.ui

import androidx.annotation.DrawableRes
import app.familytv.R
import app.familytv.data.CategoryEntity
import app.familytv.data.Names
import app.familytv.iptv.SeriesTitles

/*
 * How the TV's glass pages sort the line's categories: movie and series categories by provider and
 * by language, live ones by genre and country. Everything comes from the category names (the same
 * rules as the Rust and Apple apps; see the parity checklist), case-insensitive.
 */

/** The Providers shelf, in its order. A tile shows only when the line has a category it matches. */
enum class Provider(val label: String, @DrawableRes val art: Int, val glow: Long, pattern: String) {
    Netflix("Netflix", R.drawable.provider_netflix, 0xFFE50914, """\bNETFLIX\b"""),
    AppleTv("Apple TV+", R.drawable.provider_apple, 0xFF9AA4B2, """\bAPPLE ?(\+|TV)"""),
    Prime("Prime Video", R.drawable.provider_prime, 0xFF00A8E1, """\b(AMAZONE?|PRIME VIDEO)\b"""),
    Disney("Disney+", R.drawable.provider_disney, 0xFF1A3CD6, """\bDISNEY"""),
    Crunchyroll("Crunchyroll", R.drawable.provider_crunchyroll, 0xFFF47521, """\bCRUNCHYROLL\b"""),
    Osn("OSN+", R.drawable.provider_osn, 0xFF19B5A5, """\bOSN\b"""),
    HboMax("HBO Max", R.drawable.provider_hbomax, 0xFF6A2BD9, """\bHBO\b"""),
    Discovery("Discovery+", R.drawable.provider_discovery, 0xFF2D7FE0, """\bDISCOVERY"""),
    Viaplay("Viaplay", R.drawable.provider_viaplay, 0xFFE0177E, """\bVIAPLAY\b"""),
    Sky("Sky", R.drawable.provider_sky, 0xFF2F7BE6, """\bSKY\b"""),
    Showtime("Showtime", R.drawable.provider_showtime, 0xFFC4161C, """\bSHOWTIME\b"""),
    // Paramount Pictures goes under Paramount+.
    Paramount("Paramount+", R.drawable.provider_paramount, 0xFF0064FF, """\bPARAMOUNT\b"""),
    Peacock("Peacock", R.drawable.provider_peacock, 0xFFE8B10E, """\bPEACOCK\b"""),
    Nickelodeon("Nickelodeon", R.drawable.provider_nickelodeon, 0xFFFF7A1A, """\b(NICKELODEON|NICK JR)\b"""),
    Marvel("Marvel", R.drawable.provider_marvel, 0xFFE62429, """\bMARVEL\b"""),
    Fifa("FIFA+", R.drawable.provider_fifa, 0xFF2E6BD6, """\bFIFA\b"""),
    DreamWorks("DreamWorks", R.drawable.provider_dreamworks, 0xFF3E8BE8, """\bDREAMWORKS\b"""),
    Universal("Universal", R.drawable.provider_universal, 0xFF9AA4B2, """\bUNIVERSAL\b"""),
    ;

    // The name must start with the provider: "FR - NETFLIX FILMS" stays with French.
    private val regex = Regex("""^\s*(?:$pattern)""", RegexOption.IGNORE_CASE)

    fun matches(categoryName: String): Boolean = regex.containsMatchIn(Names.clean(categoryName))
}

/**
 * The Languages and Regions rows on the Movies and TV Shows homes ([region] says which), each in
 * this order. A group shows only when the line has a category it matches. Labels are plain
 * names: a language ("Hindi") or a place ("Pakistan"), never a mix like "Pakistani".
 */
enum class LanguageGroup(val label: String, pattern: String, val region: Boolean = false) {
    English("English", """^(EN\s*[-|]|ENGLISH\b)"""),
    Hindi("Hindi", """^(IN\s*[-|]|INDIAN?\b|HINDI\b)"""),
    Pakistani("Pakistan", """^(PK\s*[-|]|PAKISTANI?\b)""", region = true),
    Persian("Persian", """^((IR|FA)\s*[-|]|PERSIAN\b|FARSI\b|IRAN\b)"""),
    Philippines("Philippines", """^(PH\s*[-|]|PHILIPPINES?\b|FILIPINO\b|TAGALOG\b)""", region = true),
    Arabic("Arabic", """^(AR\s*[-|]|ARABIC\b)"""),
    French("French", """^(FR\s*[-|]|FRANCE\b|FRENCH\b)"""),
    Quebec("Québec", """^(QC\s*[-|]|QU[EÉ]BEC\b)""", region = true),
    Bengali("Bengali", """^(BN\s*[-|]|BENGALI\b|BANGLA)"""),
    Japanese("Japanese", """^(JP\s*[-|]|JAPAN(ESE)?\b)"""),
    Turkish("Turkish", """^(TR\s*[-|]|TURKISH\b)"""),
    Spanish("Spanish", """^(ES\s*[-|]|SPANISH\b|ESPA[NÑ]OL\b)"""),
    African("Africa", """^(AF\s*[-|]|AFRICAN?\b)""", region = true),
    Somali("Somali", """^(SO\s*[-|]|SOMALI\b)"""),
    Asian("Asia", """^ASIA(N)?\b""", region = true),
    ;

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    fun matches(categoryName: String): Boolean = regex.containsMatchIn(Names.clean(categoryName).trim())
}

/** Live TV's genre column, in its order; [Other] holds what names no genre and no country. */
enum class LiveGenre(val label: String, pattern: String?) {
    News("News", """NEWS|NOTICIAS|NOTIZIE|NACHRICHTEN|HABER|LAJME|INFORMACYJNE|الاخبار|الأخبار"""),
    Sports("Sports", null),
    Entertainment("Entertainment", """GENERAL|GÉNÉRAL|ENTERTAINMENT|UNTERHALTUNG|VERMAAK|ALGEMEEN|OGÓLNO|GJENERALE|REALITY|COMEDY|HUMOR|SERIES|DIZI|ترفيه|الترفيهية"""),
    Movies("Movies", """MOVIE|CINEMA|CINÉMA|\bCINE\b|FILM|FIMLER|BOX OFFICE|HOLLYWOOD|BOLLYWOOD"""),
    Kids("Kids", """KIDS|CHILDREN|CARTOON|ENFANTS|BAMBINI|KINDEREN|INFANTIL|CRIANCAS|CRIANÇAS|DZIECI|ΠΑΙΔΙΑ|ÇOCUK|FEMIJ|اطفال|أطفال"""),
    Documentary("Documentary", """DOCU|DOKU|DOKUMENT|BELGESEL|ΝΤΟΚΙΜΑΝΤΕΡ|وثائقي"""),
    Music("Music", """MUSIC|MUSIQUE|MUSICA|MÚSICA|MUZIEK|MÜZİK|MUZIK|MUZYCZNE|ΜΟΥΣΙΚΗ|ANGHAMI|\bRADIO\b|موسيقى|أغاني"""),
    Religious("Religious", """ISLAM|QURAN|QUR'AN|MUSLIM|CHRISTIAN|CHRISTEM|RELIGIO|GOSPEL|CATHOLIC|GURBANI|SIKH|\bNAATS?\b|FETARE|MAJD|مسيحية|اسلامية|القران"""),
    Adult("Adult", null),
    Other("Other", null),
    ;

    internal val regex = pattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    companion object {
        /** Most specific first: religious and kids lists also say "TV", news lists also say "GENERAL" now and then. */
        private val MATCH_ORDER = listOf(Religious, Kids, News, Documentary, Music, Movies)

        /**
         * The genre of a live category named [name]. Sports uses the Sports tab's rule
         * ([Sport.isSportsCategory]); a country's own list with no genre word ("CA| CANADA HD") is
         * that country's general channels, so Entertainment.
         */
        fun of(name: String, adult: Boolean = false): LiveGenre {
            if (adult) return Adult
            val n = Names.clean(name)
            MATCH_ORDER.firstOrNull { it.regex!!.containsMatchIn(n) }?.let { return it }
            if (Sport.isSportsCategory(n)) return Sports
            if (Entertainment.regex!!.containsMatchIn(n) || liveCountry(n) != null) return Entertainment
            return Other
        }
    }
}

/** Countries that lead Live TV's country column, in this order; the rest follow by channel count. */
val LEADING_COUNTRIES = listOf("Canada", "USA", "UK", "India", "Pakistan", "Iran", "Philippines")

/** "Other" in the country column: categories whose name gives no country. */
const val OTHER_COUNTRY = "Other"

private val PREFIX = Regex("""^\s*([A-Z0-9]{2,4})\s*\|""")

private val CODES = mapOf(
    "CA" to "Canada", "US" to "USA", "UK" to "UK", "IE" to "Ireland", "DE" to "Germany", "AT" to "Austria",
    "NL" to "Netherlands", "BE" to "Belgium", "IT" to "Italy", "FR" to "France", "ES" to "Spain", "PT" to "Portugal",
    "CH" to "Switzerland", "PL" to "Poland", "GR" to "Greece", "CY" to "Cyprus", "SE" to "Sweden", "DK" to "Denmark",
    "NO" to "Norway", "FI" to "Finland", "IS" to "Iceland", "HU" to "Hungary", "RO" to "Romania", "AL" to "Albania",
    "RU" to "Russia", "IL" to "Israel", "AU" to "Australia", "NZ" to "New Zealand", "MA" to "Malta", "CZ" to "Czechia",
    "SR" to "Serbia", "BH" to "Bosnia", "HR" to "Croatia", "MK" to "North Macedonia", "SI" to "Slovenia",
    "CG" to "Montenegro", "BG" to "Bulgaria", "TR" to "Türkiye", "KU" to "Kurdistan", "IR" to "Iran",
    "AG" to "Afghanistan", "AFR" to "Africa", "AR" to "Arabic", "BR" to "Brazil", "SU" to "Suriname",
    "LA" to "Latin America", "BO" to "Bolivia", "CR" to "Caribbean", "JP" to "Japan", "TN" to "Taiwan",
    "PH" to "Philippines", "GE" to "Georgia", "AZ" to "Azerbaijan", "UZ" to "Uzbekistan", "AM" to "Armenia",
    "VE" to "Venezuela", "HK" to "Hong Kong", "VI" to "Vietnam", "ID" to "Indonesia", "KR" to "South Korea",
    "TH" to "Thailand", "KZ" to "Kazakhstan", "LT" to "Lithuania", "PK" to "Pakistan", "IN" to "India",
    "BD" to "Bangladesh", "LK" to "Sri Lanka", "NP" to "Nepal", "EG" to "Egypt", "SA" to "Saudi Arabia",
    "AE" to "UAE", "KW" to "Kuwait", "QA" to "Qatar", "LB" to "Lebanon", "SY" to "Syria", "IQ" to "Iraq",
    "JO" to "Jordan", "MX" to "Mexico", "CO" to "Colombia", "CL" to "Chile", "PE" to "Peru", "EC" to "Ecuador",
    "DO" to "Dominican Republic", "PR" to "Puerto Rico", "JM" to "Jamaica", "NG" to "Nigeria", "KE" to "Kenya",
    "ZA" to "South Africa", "GH" to "Ghana", "ET" to "Ethiopia", "SO" to "Somalia", "CN" to "China",
    "SG" to "Singapore", "TW" to "Taiwan", "KH" to "Cambodia", "UA" to "Ukraine", "LV" to "Latvia",
    "EE" to "Estonia", "SK" to "Slovakia", "BY" to "Belarus", "MD" to "Moldova", "LU" to "Luxembourg",
)

/** Prefixes before "|" that aren't countries, so an unknown code never becomes a country of its own. */
private val NOT_COUNTRIES = setOf(
    "VIP", "PPV", "NEW", "TOP", "HD", "FHD", "UHD", "SD", "HQ", "LQ", "EPL", "NBA", "NFL", "NHL", "MLB", "UFC",
    "WWE", "MMA", "BT", "SKY", "TNT", "VOD", "XXX", "TV", "DTH", "OTT", "EPG",
)

/** Country words a name without a code prefix may carry, and what an ambiguous code means with them. */
private val WORDS = listOf(
    "UKRAINE" to "Ukraine", "CHINA" to "China", "MALAYSIA" to "Malaysia", "PAKISTAN" to "Pakistan",
    "BANGLADESH" to "Bangladesh", "NEPAL" to "Nepal", "SINHALA" to "Sri Lanka", "CANADA" to "Canada",
    "USA" to "USA", "UNITED STATES" to "USA", "UNITED KINGDOM" to "UK", "IRAN" to "Iran", "PERSIAN" to "Iran",
    "AFGHANISTAN" to "Afghanistan", "PHILIPPINES" to "Philippines", "PHILIPINES" to "Philippines", "INDIA" to "India",
).map { (w, c) -> Regex("""\b$w\b""", RegexOption.IGNORE_CASE) to c }

/**
 * The country of a live category named [name], or null when it gives none. Most lines start names
 * with a code ("CA| NEWS EN"); a few codes are shared ("UK| UKRAINE", "CH| CHINA", "MA| MALAYSIA",
 * "HR| HORSE RACING"), so a country word in the name wins. Asian lists ("ASIA| PAKISTAN") are India
 * unless they name another country.
 */
fun liveCountry(name: String): String? {
    val n = Names.clean(name)
    val word = WORDS.firstOrNull { it.first.containsMatchIn(n) }?.second
    val code = PREFIX.find(n)?.groupValues?.get(1)?.uppercase()
    return when {
        word != null -> word
        code == "ASIA" -> "India"
        code == "HR" && n.contains("HORSE", ignoreCase = true) -> null
        code == null -> null
        else -> CODES[code]
            // An unknown country code ("XY| NEWS") still gets its own row rather than vanishing into Other.
            ?: code.takeIf { it.length <= 3 && it.all(Char::isLetter) && it !in NOT_COUNTRIES && !SeriesTitles.isLanguageCode(it) }
    }
}

/** The live country column's order: [LEADING_COUNTRIES], then by [count] (most first), then [OTHER_COUNTRY]. */
fun orderCountries(count: Map<String, Int>): List<String> =
    count.keys.sortedWith(
        compareBy<String>({ it == OTHER_COUNTRY }, { LEADING_COUNTRIES.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { -(count[it] ?: 0) }, { it }),
    )

/** The categories of [cats] in [provider]. */
fun providerCategories(cats: List<CategoryEntity>, provider: Provider): List<CategoryEntity> = cats.filter { provider.matches(it.name) }

/** The categories of [cats] in [group]. */
fun languageCategories(cats: List<CategoryEntity>, group: LanguageGroup): List<CategoryEntity> = cats.filter { group.matches(it.name) }
