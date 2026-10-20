package app.familytv.data

import app.familytv.iptv.SeriesTitles
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
     * A channel's name as listed and captioned: [clean], without a leading country or language
     * code ("CA EN: CBC Toronto" is "CBC Toronto", also "[UK] Sky", "US| ESPN"). Only capital codes
     * go, so "TSN: Live" stays. Guide matching and country grouping read the raw name, code and all.
     */
    fun channel(raw: String): String {
        val s = clean(raw)
        val m = CODE_PREFIX.find(s) ?: return s
        if (!m.groupValues[1].split(CODE_SPLIT).all { it in COUNTRY_CODES || SeriesTitles.isLanguageCode(it) }) return s
        return s.substring(m.range.last + 1).ifBlank { s }
    }

    private val CODE_PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Z]{2,3}(?:[\\s/-]+[A-Z]{2,3})?)\\s*(?:[\\])|]\\s*[:|\\-▎•►»–—]*|[:|\\-▎•►»–—]+)\\s*")
    private val CODE_SPLIT = Regex("[\\s/-]+")
    private val COUNTRY_CODES = setOf(
        "CA", "CAN", "US", "USA", "UK", "GB", "IN", "IND", "PK", "PAK", "AU", "NZ", "IE", "FR", "DE", "ES", "IT",
        "MX", "BR", "PH", "AE", "BD", "LK", "NP", "LAT", "EU", "AF", "AS",
    )

    /**
     * A heading row, not a channel or category: "#" with only spaces, dashes and the like
     * ("######", "# - #"), three or more "#" in front, or text wrapped in "##" ("#### SPORTS ####").
     */
    fun isSeparator(raw: String): Boolean {
        val t = raw.trim()
        return '#' in t && (ONLY_MARKS.matches(t) || t.startsWith("###") || WRAPPED.matches(t))
    }
}

val ChannelEntity.displayName: String get() = Names.channel(name)
val CategoryEntity.displayName: String get() = Names.clean(name)
