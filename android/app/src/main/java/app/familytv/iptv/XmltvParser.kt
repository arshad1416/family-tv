package app.familytv.iptv

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class XmltvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String?)

/**
 * Streaming XMLTV reader. Only programmes inside [windowStart, windowEnd] and (if given) for
 * channels in [wanted] are emitted, so a 200 MB guide never has to fit in memory or the DB.
 */
object XmltvParser {
    fun parse(
        p: XmlPullParser,
        windowStart: Long,
        windowEnd: Long,
        wanted: Set<String>?,
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
                if (!keep) {
                    skip(p)
                } else {
                    var title = ""
                    var desc: String? = null
                    val depth = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                        if (p.eventType == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "title" -> if (title.isEmpty()) title = p.nextText().trim()
                                "desc" -> if (desc == null) desc = p.nextText().trim().take(600)
                                else -> skip(p)
                            }
                        }
                    }
                    onProgramme(XmltvProgramme(channel!!, start!!, stop!!, title.ifEmpty { "No information" }, desc))
                }
            }
            event = p.next()
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
