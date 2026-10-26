package app.familytv.iptv

import app.familytv.data.NowNext
import app.familytv.data.ProgrammeEntity

/**
 * Links the provider's channels to channels in the profile's extra guides (epgshare01 and the
 * like) and decides, per channel, whose schedule to show. The rules never guess: a channel only
 * links when its id or name points at one channel, and a name never crosses countries.
 */
object GuideMatch {
    /** How a channel was linked, for the admin's diagnostics. */
    enum class How { PIN, ID, ID_NAME, NAME }

    data class Link(val guideId: String, val how: How)

    /** What the provider says about one of its channels. [category] is its category's name. */
    data class Channel(val name: String, val epgId: String?, val category: String?)

    /** One extra guide's channels, looked up by id and by [key]. */
    class Index(channels: List<XmltvChannel>) {
        val ids: Set<String> = channels.mapTo(HashSet()) { it.id }
        val byKey: Map<String, List<XmltvChannel>> = HashMap<String, MutableList<XmltvChannel>>().also { m ->
            for (c in channels) {
                val names = c.names + idName(c.id)
                // "Cable Pulse 24 (CP24) HD" is also "CP24"; "Global Maritimes (CIHF)" also "CIHF".
                val aliases = names.flatMap { n -> ALIAS.findAll(n).map { it.groupValues[1] }.toList() }
                val keys = (names + aliases).map(::key).filter { it.length >= 2 }.toSet()
                for (k in keys) m.getOrPut(k) { ArrayList() } += c
            }
        }
    }

    /**
     * The guide channel for [c] in [index], or null. A [pin] (a guide channel id the admin chose)
     * wins outright; a pin for a channel in another guide stops this guide matching it by name.
     */
    fun link(c: Channel, index: Index, pin: String?): Link? {
        if (pin != null) return if (pin in index.ids) Link(pin, How.PIN) else null
        val id = c.epgId?.removePrefix(EXTRA_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && id in index.ids) return Link(id, How.ID)
        val idCountry = countryOfId(id)
        val nameCountry = countryOfName(c.name) ?: c.category?.let(::countryOfName)
        // A provider's id sometimes carries the wrong country ("CTV.Edmonton.us"): the name's country is the fallback.
        val idCountries = listOfNotNull(idCountry ?: nameCountry, nameCountry).distinct().ifEmpty { listOf(null) }
        val nameCountries = listOfNotNull(nameCountry ?: idCountry, idCountry).distinct().ifEmpty { listOf(null) }
        id?.let { i -> idCountries.firstNotNullOfOrNull { unique(index, key(idName(i)), it) } }?.let { return Link(it, How.ID_NAME) }
        for (k in nameKeys(c.name)) nameCountries.firstNotNullOfOrNull { unique(index, k, it) }?.let { return Link(it, How.NAME) }
        return null
    }

    /**
     * True when [c] is a regional station ("Global News Regina", "CTV Edmonton") whose own guide id
     * names somewhere else: providers often point every Global or CTV feed at one city's schedule.
     * Such a channel shows its name-matched extra guide even when the provider's schedule looks full.
     */
    fun ownIdIsElsewhere(c: Channel): Boolean {
        val id = c.epgId?.takeUnless { it.startsWith(EXTRA_PREFIX) }?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val own = key(idName(id))
        val place = nameKeys(c.name).firstNotNullOfOrNull { k ->
            NETWORKS.firstOrNull { k.startsWith(it) }?.let { k.removePrefix(it).removePrefix("news") }?.takeIf { it.length >= 3 }
        } ?: return false
        return place !in own
    }

    /**
     * The keys a provider's channel name goes by, best first: the name, then without a call sign in
     * brackets ("CTV Edmonton (CFRN)"), then the call sign itself, then how the guides name regional
     * news feeds ("Global News Calgary" is "Global Calgary", "CTV News Montreal" is CFCF; "Global
     * News Kingston" is CKWS).
     */
    internal fun nameKeys(name: String): List<String> {
        val full = key(name)
        val signs = ALIAS.findAll(name).map { it.groupValues[1] }.filter { CALL_SIGN.matches(it) }.map(::key).toList()
        val bare = key(name.replace(ALIAS, " "))
        val base = listOf(full, bare).distinct()
        // "Global News Calgary" is "Global Calgary", "CTV News Edmonton" is "CTV Edmonton"; not "CTV News Channel".
        val regional = base.flatMap { k ->
            NETWORKS.mapNotNull { n -> k.removePrefix(n + "news").takeIf { k.startsWith(n + "news") && it.length >= 2 && it != "channel" && it != "network" }?.let { n + it } }
        }
        val renamed = (base + regional).mapNotNull { REGIONAL[it] }
        return (base + signs + regional + renamed).distinct()
    }

    /** The one channel under [k] (copies that differ only by HD/SD count as one), or null. */
    private fun unique(index: Index, k: String, country: String?): String? {
        if (k.length < 2) return null
        val all = index.byKey[k] ?: return null
        val inCountry = if (country == null) all else all.filter { countryOfId(it.id) == country }
        // Without a country to go by, a name that several countries use is left alone.
        if (inCountry.isEmpty() || inCountry.map { countryOfId(it.id) }.distinct().size > 1) return null
        return inCountry.first().id
    }

    /**
     * Letters and digits only, lower case, without the provider's country prefix ("CA|", "[US]",
     * "IN:", "CA EN:"), picture-quality words (HD, FHD, SD) or a leading "The". "+1" stays: a channel an hour
     * behind has another schedule.
     */
    fun key(name: String): String {
        val words = stripPrefix(name).lowercase()
            .replace("ᴴᴰ", " hd ").replace("ʰᴰ", " hd ").replace("ꜰʜᴅ", " fhd ")
            .split(NOT_WORD).filter { it.isNotEmpty() && it !in QUALITY }
        return words.dropWhile { it == "the" }.joinToString("")
    }

    /** A guide id as words: "CTV.Toronto.HD.ca2" is "CTV Toronto HD". */
    fun idName(id: String): String = (if (countryOfId(id) != null) id.replace(SUFFIX, "") else id).replace('.', ' ')

    /** The country an id's suffix names: "ca" for "CTV.Toronto.HD.ca2" or "ctv.ca"; null for "Discovery.HD". */
    fun countryOfId(id: String?): String? = id?.let { SUFFIX.find(it) }?.groupValues?.get(1)?.lowercase()?.let { COUNTRIES[it] }

    /** The country a channel or category name's prefix or words name ("CA| CTV", "CA EN: CTV", "USA Entertainment"). */
    fun countryOfName(name: String): String? {
        // The prefix's first word: "CA EN:" is Canada, its "EN" the language.
        PREFIX.find(name)?.let { m -> COUNTRIES[m.value.lowercase().split(NOT_WORD).firstOrNull { it.isNotEmpty() }]?.let { return it } }
        val words = name.lowercase().split(NOT_WORD)
        return words.firstNotNullOfOrNull { COUNTRY_WORDS[it] }
    }

    private fun stripPrefix(name: String): String {
        var s = name
        repeat(3) { s = s.replaceFirst(PREFIX, "") }
        return s
    }

    /**
     * Share of [from]..[until] the programmes cover with real titles (0..1). Placeholders like
     * "No information" or the channel's own name don't count.
     */
    fun coverage(programmes: List<NowNext>, channelName: String, from: Long, until: Long): Float {
        if (until <= from) return 0f
        var cursor = from
        var covered = 0L
        for (p in programmes.sortedBy { it.start }) {
            if (!isRealTitle(p.title, channelName)) continue
            val s = maxOf(p.start, cursor)
            val e = minOf(p.stop, until)
            if (e > s) { covered += e - s; cursor = e }
        }
        return covered.toFloat() / (until - from)
    }

    fun isRealTitle(title: String, channelName: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || PLACEHOLDER.matches(t)) return false
        val k = GuideTitles.key(t)
        return k.isNotEmpty() && k != GuideTitles.key(channelName) && key(t) != key(channelName)
    }

    /**
     * The provider's programmes with gaps filled from the extra guide: same show (same cleaned
     * title) starting within five minutes. Only empty fields are filled, and a description only
     * when the extra one is longer. Returns just the rows that changed.
     */
    fun fill(provider: List<ProgrammeEntity>, extra: List<ProgrammeEntity>): List<ProgrammeEntity> {
        if (extra.isEmpty()) return emptyList()
        val changed = ArrayList<ProgrammeEntity>()
        for (p in provider) {
            val k = GuideTitles.key(GuideTitles.clean(p.title))
            val x = extra.firstOrNull { Math.abs(it.start - p.start) <= FILL_WINDOW_MS && GuideTitles.key(GuideTitles.clean(it.title)) == k } ?: continue
            val desc = if ((x.description?.length ?: 0) > (p.description?.length ?: 0)) x.description else p.description
            val f = p.copy(
                description = desc,
                subTitle = p.subTitle ?: x.subTitle,
                season = p.season ?: x.season,
                episode = p.episode ?: x.episode,
                category = p.category ?: x.category,
                icon = p.icon ?: x.icon,
                rating = p.rating ?: x.rating,
                year = p.year ?: x.year,
                isNew = p.isNew || x.isNew,
                cast = p.cast ?: x.cast,
            )
            if (f != p) changed += f
        }
        return changed
    }

    /** Programmes from an extra guide are stored under this prefix plus the guide's channel id. */
    const val EXTRA_PREFIX = "x:"

    /** The provider's schedule is kept when it covers this much of the next day. */
    const val GOOD_COVERAGE = 0.8f

    private const val FILL_WINDOW_MS = 5 * 60_000L
    private val NOT_WORD = Regex("""[^\p{L}\p{N}+]+""")
    private val QUALITY = setOf("hd", "fhd", "sd", "hq", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "50fps", "60fps", "backup")
    /** A short code in brackets, such as a call sign or "CP24"; words like "(Pacific)" are not aliases. */
    private val ALIAS = Regex("""\(([A-Z0-9][A-Z0-9-]{2,7})\)""")
    private val SUFFIX = Regex("""\.([a-zA-Z]{2})\d*$""")
    private val PREFIX = Regex("""^\s*(?:\[[^\]]{1,10}]|\([^)]{1,10}\)|[\p{L}\d]{1,8}(?:\s+\p{L}{2,3})?\s*[|:]|24/7)\s*""")
    /** Networks whose feeds are regional stations: "Global News Regina" is Global plus a place. */
    private val NETWORKS = listOf("citytv", "global", "ctv", "cbc")
    /** A call sign or short code worth matching on its own: "CFRN", "CFCF-DT", "CP24". Not "EVENT". */
    private val CALL_SIGN = Regex("""C[A-Z]{3}(-[A-Z]{2})?|[A-Z]{2,3}\d{1,3}""")
    /** Regional stations the guides list under another name, by key (after "Global News X" became "Global X"). */
    private val REGIONAL = mapOf(
        "globalhalifax" to "globalmaritimes",
        "globalkingston" to "ckws",
        "globalwinnipeg" to "globalmanitoba",
        "globalvancouver" to "globalbc",
        "ctvmontreal" to "cfcf",
    )
    private val PLACEHOLDER = Regex("""(?i)no (programme |program |epg )?(information|info|data)|to be announced|tba|tbd|no epg|off air|n/?a""")
    private val COUNTRIES = mapOf(
        "ca" to "ca", "can" to "ca", "canada" to "ca",
        "us" to "us", "usa" to "us",
        "in" to "in", "ind" to "in", "india" to "in",
        "uk" to "uk", "gb" to "uk",
        "pk" to "pk", "pak" to "pk",
        "au" to "au", "nz" to "nz", "ie" to "ie", "fr" to "fr", "de" to "de", "es" to "es", "it" to "it",
        "mx" to "mx", "br" to "br", "ph" to "ph", "ae" to "ae", "bd" to "bd", "lk" to "lk", "np" to "np",
    )
    // Country words only: language words ("Punjabi") also name Canadian channels such as ATN Punjabi.
    private val COUNTRY_WORDS = mapOf(
        "canada" to "ca", "canadian" to "ca",
        "usa" to "us",
        "india" to "in", "indian" to "in",
        "uk" to "uk", "british" to "uk",
        "pakistan" to "pk",
    )
}
