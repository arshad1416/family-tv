package app.familytv.data

import java.text.Normalizer

/**
 * Provider names as shown on screen and searched. Providers style names with superscript and
 * small-capital letters ("ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), and pad lists with heading rows ("##### 4K #####").
 * Only display and search use this: stored names, the portal's category lists and
 * [app.familytv.iptv.StreamQuality] (which reads "ᴴᴰ" and the like) keep the raw name.
 */
object Names {
    // Small capitals that NFKC leaves alone, as the capitals they stand for.
    private val SMALL_CAPS = mapOf(
        'ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ꜰ' to 'F', 'ɢ' to 'G', 'ʜ' to 'H', 'ɪ' to 'I',
        'ᴊ' to 'J', 'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O', 'ᴘ' to 'P', 'ꞯ' to 'Q', 'ʀ' to 'R',
        'ꜱ' to 'S', 'ᴛ' to 'T', 'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z',
    )
    private val ONLY_MARKS = Regex("[#\\s\\-=_*~]+")
    private val WRAPPED = Regex("#{2,}.*#{2,}")

    /** [raw] as shown: NFKC ("ᴿᴬᵂ ⁶⁰ᶠᵖˢ" is "RAW 60fps"), small capitals as capitals. */
    fun clean(raw: String): String {
        if (raw.all { it.code < 0x80 }) return raw
        val n = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return buildString(n.length) { n.forEach { append(SMALL_CAPS[it] ?: it) } }
    }

    /**
     * A heading row, not a channel or category: "#" with only spaces, dashes and the like
     * ("######", "# - #"), three or more "#" in front, or text wrapped in "##" ("#### SPORTS ####").
     */
    fun isSeparator(raw: String): Boolean {
        val t = raw.trim()
        return '#' in t && (ONLY_MARKS.matches(t) || t.startsWith("###") || WRAPPED.matches(t))
    }
}

val ChannelEntity.displayName: String get() = Names.clean(name)
val CategoryEntity.displayName: String get() = Names.clean(name)
