package app.familytv.iptv

import app.familytv.data.SearchText

/** Settings' Stream quality choice, one each for Live TV, Movies and Series. */
enum class QualityPref(val label: String) {
    AUTO("Auto"),
    UHD("Always prefer 4K"),
    HD("Always prefer HD"),
    FHD("Always prefer 1080p"),
    P720("Always prefer 720p"),
}

/**
 * Quality of a copy or a channel version, and which one a [QualityPref] picks. Pure, unit tested.
 *
 * A copy's quality comes from its name ("4K", "UHD", "2160p" = 4K; "FHD", "1080p" = 1080p; "HD" =
 * HD; "720p" = 720p; "SD", "480p" = SD), else its picture height when the player knows it. HD and
 * 720p sit on one rung: "Always prefer HD" takes an HD-labelled copy over a 720p-labelled one, and
 * the other way round. When the preferred quality isn't there: the nearest lower, then the nearest
 * higher. A copy whose quality isn't known ranks just after an exact match, since an untagged copy
 * is usually the provider's standard one.
 */
object StreamQuality {
    enum class Q(val rung: Int) { SD(0), HD(1), P720(1), FHD(2), UHD(3) }

    private val TOKENS = mapOf(
        "4k" to Q.UHD, "8k" to Q.UHD, "uhd" to Q.UHD, "2160p" to Q.UHD, "2160" to Q.UHD,
        "fhd" to Q.FHD, "1080p" to Q.FHD, "1080i" to Q.FHD, "1080" to Q.FHD,
        "hd" to Q.HD,
        "720p" to Q.P720, "720" to Q.P720,
        "sd" to Q.SD, "480p" to Q.SD, "576p" to Q.SD, "480" to Q.SD, "576" to Q.SD,
    )

    /** Words that only describe how a channel is sent, dropped when matching its versions. */
    private val TECH = setOf("hq", "lq", "hevc", "h265", "h264", "x265", "50fps", "60fps", "raw")

    private fun target(pref: QualityPref): Q? = when (pref) {
        QualityPref.AUTO -> null
        QualityPref.UHD -> Q.UHD
        QualityPref.HD -> Q.HD
        QualityPref.FHD -> Q.FHD
        QualityPref.P720 -> Q.P720
    }

    /** The quality a name says it is: the highest tag in it, or null when it has none. */
    fun of(name: String): Q? = SearchText.words(name).mapNotNull { TOKENS[it] }.maxByOrNull { it.rung }

    fun ofHeight(height: Int): Q? = when {
        height <= 0 -> null
        height >= 1800 -> Q.UHD
        height >= 900 -> Q.FHD
        height >= 650 -> Q.P720
        else -> Q.SD
    }

    /** Lower is better for [pref]; every copy scores 0 under Auto. */
    fun score(q: Q?, pref: QualityPref): Int {
        val want = target(pref) ?: return 0
        return when {
            q == null -> 5
            q == want -> 0
            q.rung == want.rung -> 1
            q.rung < want.rung -> 10 + (want.rung - q.rung)
            else -> 100 + (q.rung - want.rung)
        }
    }

    /** Index in [qualities] that [pref] picks (the first on a tie), or -1 for Auto or an empty list. */
    fun pick(qualities: List<Q?>, pref: QualityPref): Int {
        if (pref == QualityPref.AUTO || qualities.isEmpty()) return -1
        return qualities.indices.minBy { score(qualities[it], pref) }
    }

    /**
     * A channel's name without its quality tags ("CA: CTV FHD" and "CA: CTV 4K" both give "ca ctv"),
     * so versions of one channel share it; country prefixes stay. Empty when nothing is left.
     */
    fun channelKey(name: String): String = SearchText.words(name).filter { it !in TOKENS && it !in TECH }.joinToString(" ")
}
