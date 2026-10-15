package app.familytv.subs

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** What is playing, as far as subtitle matching cares. Ids come from the Xtream VOD/series info. */
data class SubtitleRequest(
    val episode: Boolean,
    /** Movie title, or the series title for an episode. */
    val title: String,
    val year: Int? = null,
    /** Movie tmdb/imdb ids, or the series' ids for an episode. */
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null,
    /** OpenSubtitles language codes in order of preference, e.g. ["en", "fr"]. */
    val languages: List<String> = listOf("en"),
)

/** What the player knows about the playing movie or episode (from its catalog row) for subtitle matching. */
data class MediaRef(
    val episode: Boolean,
    val title: String,
    val year: Int? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null,
) {
    val hasIds get() = tmdbId != null || imdbId != null

    fun request(languages: List<String>) =
        SubtitleRequest(episode, title, year, tmdbId, imdbId, season, episodeNumber, languages)
}

data class SubtitleHit(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    val trusted: Boolean,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val hashMatch: Boolean,
)

/**
 * Matching rules for OpenSubtitles (REST API v1). Pure, so the matching is unit tested:
 * ids first (tmdb, then imdb; for episodes the series id plus season/episode), then a cleaned
 * title with year as the fallback for catalogs that carry no ids.
 */
object SubtitleSearch {

    data class CleanTitle(val title: String, val year: Int?)

    private val PREFIX = Regex("""^\s*(?:[|\[(]\s*[A-Z0-9+]{2,4}\s*[|\])]\s*[-:]?|(?:[A-Z]{2}\s*[-:|]|[A-Z]{3}\s*[-|])\s*)""")
    private val TAGS = Regex("""\[[^]]*]|\b(?:4K|UHD|FHD|HD|HEVC|H\.?26[45]|x26[45]|HDR10?|MULTI|VOSTFR|1080p|720p|2160p)\b""", RegexOption.IGNORE_CASE)
    private val YEAR_PAREN = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val YEAR_TAIL = Regex("""[\s.\-]((?:19|20)\d{2})\s*$""")
    private val EPISODE_TAG = Regex("""\bS\d{1,2}\s*E\d{1,3}\b.*$""", RegexOption.IGNORE_CASE)

    /**
     * Provider titles look like "EN - The Matrix (1999) [4K]" or "|UK| Show Name 2019". Strips the
     * country prefix, quality tags and episode tags, and pulls out the year.
     */
    fun cleanTitle(raw: String): CleanTitle {
        var t = raw.trim()
        // Country/language prefixes can repeat ("|EN| |4K| Title"): strip until nothing changes.
        while (true) {
            val next = PREFIX.replaceFirst(t, "").trim()
            if (next == t) break
            t = next
        }
        val year = YEAR_PAREN.find(t)?.groupValues?.get(1)?.toInt() ?: YEAR_TAIL.find(t)?.groupValues?.get(1)?.toInt()
        t = YEAR_PAREN.replace(t, " ")
        t = TAGS.replace(t, " ")
        t = EPISODE_TAG.replace(t, " ")
        if (year != null) t = YEAR_TAIL.replace(t, " ")
        t = t.replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim { it.isWhitespace() || it in "|-:" }
        return CleanTitle(t.ifEmpty { raw.trim() }, year)
    }

    /** "tt0133093" → "133093" (the API wants the number without leading zeros). */
    fun imdbNumber(id: String?): String? = id?.trim()?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    fun tmdbNumber(id: String?): String? = id?.trim()?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** First 4-digit year found in "2019-05-31", "2019" or "31/05/2019". */
    fun year(text: String?): Int? = text?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toInt() }

    /** Maps a device locale to OpenSubtitles' codes ("pt-BR" → "pt-br", "zh-TW" → "zh-tw", otherwise the language). */
    fun languageCode(locale: Locale): String {
        val lang = locale.language.lowercase(Locale.ROOT).let { if (it == "iw") "he" else if (it == "in") "id" else it }
        return when {
            lang == "pt" && locale.country.equals("BR", true) -> "pt-br"
            lang == "pt" -> "pt-pt"
            lang == "zh" && (locale.country.equals("TW", true) || locale.country.equals("HK", true)) -> "zh-tw"
            lang == "zh" -> "zh-cn"
            else -> lang
        }
    }

    /**
     * The searches to try, best first. Each is a sorted parameter list: the API asks for
     * alphabetical, lower-case parameters and redirects otherwise.
     */
    fun queries(r: SubtitleRequest): List<List<Pair<String, String>>> {
        val langs = r.languages.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
        val common = buildList { if (langs.isNotEmpty()) add("languages" to langs) }
        val tmdb = tmdbNumber(r.tmdbId)
        val imdb = imdbNumber(r.imdbId)
        val clean = cleanTitle(r.title)
        val year = r.year ?: clean.year
        val out = ArrayList<List<Pair<String, String>>>()
        if (r.episode) {
            val se = buildList {
                r.season?.let { add("season_number" to "$it") }
                r.episodeNumber?.let { add("episode_number" to "$it") }
            }
            if (tmdb != null) out += common + se + ("parent_tmdb_id" to tmdb)
            if (imdb != null) out += common + se + ("parent_imdb_id" to imdb)
            out += common + se + listOf("query" to clean.title.lowercase(Locale.ROOT), "type" to "episode")
        } else {
            if (tmdb != null) out += common + ("tmdb_id" to tmdb)
            if (imdb != null) out += common + ("imdb_id" to imdb)
            out += common + listOfNotNull("query" to clean.title.lowercase(Locale.ROOT), "type" to "movie", year?.let { "year" to "$it" })
        }
        return out.map { q -> q.sortedBy { it.first } }
    }

    fun parse(json: String): List<SubtitleHit> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val a = item.obj()?.get("attributes")?.obj() ?: return@mapNotNull null
            val file = a.get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.obj() ?: return@mapNotNull null
            val fileId = file.get("file_id")?.longOrNull() ?: return@mapNotNull null
            val feature = a.get("feature_details")?.obj()
            SubtitleHit(
                fileId = fileId,
                language = a.str("language")?.lowercase(Locale.ROOT).orEmpty(),
                release = a.str("release") ?: file.str("file_name").orEmpty(),
                downloads = a.get("download_count")?.longOrNull()?.toInt() ?: 0,
                hearingImpaired = a.bool("hearing_impaired"),
                machineTranslated = a.bool("machine_translated") || a.bool("ai_translated"),
                trusted = a.bool("from_trusted"),
                season = feature?.get("season_number")?.longOrNull()?.toInt(),
                episode = feature?.get("episode_number")?.longOrNull()?.toInt(),
                year = feature?.get("year")?.longOrNull()?.toInt(),
                hashMatch = a.bool("moviehash_match"),
            )
        }
    }

    /**
     * Drops results that are clearly for something else (another episode, another year) and orders
     * the rest: preferred language, human-made, trusted, then most downloaded.
     */
    fun rank(hits: List<SubtitleHit>, r: SubtitleRequest): List<SubtitleHit> {
        val langs = r.languages.map { it.lowercase(Locale.ROOT) }
        val year = r.year ?: cleanTitle(r.title).year
        return hits
            .filter { h -> !r.episode || ((r.season == null || h.season == null || h.season == r.season) && (r.episodeNumber == null || h.episode == null || h.episode == r.episodeNumber)) }
            .filter { h -> r.episode || year == null || h.year == null || kotlin.math.abs(h.year - year) <= 1 }
            .sortedWith(
                compareBy<SubtitleHit> { h -> langs.indexOf(h.language).let { if (it < 0) langs.size else it } }
                    .thenByDescending { it.hashMatch }
                    .thenBy { it.machineTranslated }
                    .thenByDescending { it.trusted }
                    .thenByDescending { it.downloads },
            )
            .distinctBy { it.fileId }
    }

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.bool(k: String): Boolean = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
    private fun JsonElement.longOrNull(): Long? = takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.toDouble().toLong() }.getOrNull() }
}
