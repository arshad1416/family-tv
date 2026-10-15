package app.familytv.iptv

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One programme from an XMLTV guide, with everything the guide header shows. [season] and
 * [episode] are 1-based; [rating] prefers the Canadian system, then the US one.
 */
data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val subTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val categories: List<String> = emptyList(),
    val icon: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val isNew: Boolean = false,
    val cast: List<String> = emptyList(),
)

/**
 * Streaming XMLTV reader. Only programmes inside [windowStart, windowEnd] and (if given) for
 * channels in [wanted] are emitted, so a 200 MB guide never has to fit in memory or the DB.
 * Where a guide gives a title or description in several languages, [lang]'s wins.
 */
object XmltvParser {
    fun parse(
        p: XmlPullParser,
        windowStart: Long,
        windowEnd: Long,
        wanted: Set<String>?,
        lang: String? = null,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "programme") {
                val channel = p.getAttributeValue(null, "channel")
                val start = parseTime(p.getAttributeValue(null, "start"))
                val stop = parseTime(p.getAttributeValue(null, "stop"))
                val keep = channel != null && start != null && stop != null &&
                    stop >= windowStart && start <= windowEnd && (wanted == null || channel in wanted)
                if (!keep) skip(p) else onProgramme(programme(p, channel!!, start!!, stop!!, lang))
            }
            event = p.next()
        }
    }

    private fun programme(p: XmlPullParser, channel: String, start: Long, stop: Long, lang: String?): XmltvProgramme {
        val titles = Localised(lang)
        val descs = Localised(lang)
        val subTitles = Localised(lang)
        var episodeNs: String? = null
        var onscreen: String? = null
        val categories = LinkedHashSet<String>()
        var icon: String? = null
        val ratings = ArrayList<Pair<String?, String>>()
        var year: Int? = null
        var isNew = false
        val cast = ArrayList<String>()
        children(p) {
            when (p.name) {
                "title" -> titles.add(p)
                "desc" -> descs.add(p)
                "sub-title" -> subTitles.add(p)
                "episode-num" -> when (p.getAttributeValue(null, "system")) {
                    "xmltv_ns" -> episodeNs = text(p)
                    "onscreen", null -> onscreen = text(p)
                    else -> skip(p)
                }
                "category" -> text(p)?.let { if (categories.size < 3) categories += it }
                "icon" -> { if (icon == null) icon = p.getAttributeValue(null, "src")?.trim()?.takeIf { it.startsWith("http") }; skip(p) }
                "rating" -> {
                    val system = p.getAttributeValue(null, "system")
                    children(p) { if (p.name == "value") text(p)?.let { ratings += system to it } else skip(p) }
                }
                "date" -> year = text(p)?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
                "new" -> { isNew = true; skip(p) }
                "credits" -> children(p) { if (p.name == "actor" && cast.size < 5) text(p)?.let(cast::add) else skip(p) }
                else -> skip(p)
            }
        }
        val (season, episode) = episodeNumbers(episodeNs, onscreen)
        return XmltvProgramme(
            channel, start, stop,
            title = titles.best() ?: "No information",
            description = descs.best()?.take(1000),
            subTitle = subTitles.best(),
            season = season,
            episode = episode,
            categories = categories.toList(),
            icon = icon,
            rating = pickRating(ratings),
            year = year,
            isNew = isNew,
            cast = cast,
        )
    }

    /**
     * Season and episode, 1-based: xmltv_ns ("1.4.0/2" is season 2, episode 5; parts may be empty)
     * first, else an on-screen form like "S02E05" or "S2 E5".
     */
    fun episodeNumbers(xmltvNs: String?, onscreen: String?): Pair<Int?, Int?> {
        xmltvNs?.split('.')?.takeIf { it.size >= 2 }?.let { parts ->
            fun n(s: String) = s.substringBefore('/').trim().toIntOrNull()?.takeIf { it >= 0 }?.plus(1)
            val season = n(parts[0])
            val episode = n(parts[1])
            if (season != null || episode != null) return season to episode
        }
        onscreen?.let { ONSCREEN.find(it) }?.let { m -> return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull() }
        return null to null
    }

    /** The Canadian rating, else the US one, else the first. */
    fun pickRating(ratings: List<Pair<String?, String>>): String? =
        (ratings.firstOrNull { it.first?.contains("canad", ignoreCase = true) == true }
            ?: ratings.firstOrNull { it.first?.let { s -> s.contains("usa", true) || s.contains("mpaa", true) || s.contains("vchip", true) } == true }
            ?: ratings.firstOrNull())?.second

    private val ONSCREEN = Regex("""(?i)S(\d{1,3})\s*[:.]?\s*E(\d{1,4})""")

    /** Keeps the first value, unless a later one is in [lang]. */
    private class Localised(private val lang: String?) {
        private var value: String? = null
        private var matched = false
        fun add(p: XmlPullParser) {
            val l = p.getAttributeValue(null, "lang")
            val t = text(p) ?: return
            val isLang = lang != null && l != null && l.substringBefore('-').equals(lang, ignoreCase = true)
            if (value == null || (isLang && !matched)) { value = t; matched = isLang }
        }
        fun best() = value
    }

    /** The element's text, trimmed; null when empty. Leaves the parser on its end tag. */
    private fun text(p: XmlPullParser): String? = runCatching { p.nextText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Calls [onChild] on each direct child element; [onChild] must consume it (read or skip). */
    private inline fun children(p: XmlPullParser, onChild: () -> Unit) {
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) return
            if (e == XmlPullParser.START_TAG) onChild()
        }
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private val fmtWithZone = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }
    private val fmtUtc = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    /** "20261014183000 +0000" or "20261014183000" (treated as UTC). */
    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return runCatching {
            if (t.length > 14 && (t.contains('+') || t.contains('-'))) fmtWithZone.get()!!.parse(t)!!.time
            else fmtUtc.get()!!.parse(t.take(14))!!.time
        }.getOrNull()
    }
}
