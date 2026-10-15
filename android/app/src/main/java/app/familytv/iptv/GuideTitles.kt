package app.familytv.iptv

/** Programme titles as guides write them, cleaned for looking the show up elsewhere. */
object GuideTitles {
    private val NOISE = Regex("""(?i)^(new|live|premiere|season premiere|series premiere|series finale)\s*[:\-]\s*|\s*\((r|new|live|hd|cc|\d{4})\)|\s+(19|20)\d{2}\s*$""")
    private val SKIP_CATEGORIES = Regex("""(?i)\b(news|sports?|weather|paid programming|infomercial|shopping)\b""")
    private val SKIP_TITLES = Regex("""(?i)^(nhl|nba|nfl|mlb|mls|ufc|wwe|pga|nascar|f1|premier league|champions league)\b|^(no information|to be announced|tba|off air|sign off)$""")

    fun clean(title: String): String = title.replace(NOISE, "").trim()

    /**
     * Whether a programme is worth looking up on TMDB or TVmaze: it lacks a description or a
     * picture, and isn't news, sport or the like, which those services can't describe.
     */
    fun worthLookingUp(title: String, category: String?, description: String?, icon: String?): Boolean {
        val thin = description.isNullOrBlank() || description.length < 40 || icon == null
        val t = clean(title)
        return thin && t.length >= 2 && !SKIP_TITLES.containsMatchIn(t) && (category == null || !SKIP_CATEGORIES.containsMatchIn(category))
    }

    /** Letters and digits only, for comparing a title with a search result's name. */
    fun key(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }
}
