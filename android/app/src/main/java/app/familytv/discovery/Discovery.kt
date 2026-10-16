package app.familytv.discovery

import app.familytv.debrid.RdNames
import app.familytv.iptv.SeriesTitles
import app.familytv.trakt.PlayItem
import app.familytv.trakt.TitleMatch
import app.familytv.user.ProgressKey
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

class SourceUnavailable(message: String) : IOException(message)

data class DiscoveryTitle(
    val kind: String, val tmdb: String, val title: String, val year: Int? = null,
    val poster: String? = null, val overview: String? = null, val imdb: String? = null,
    val seasons: List<Int> = emptyList(),
) {
    val key get() = "$kind/$tmdb"
}

data class DiscoveryEpisode(val season: Int, val number: Int, val name: String)

data class DiscoveryTarget(val title: DiscoveryTitle, val season: Int = 0, val episode: Int = 0) {
    val isEpisode get() = title.kind == "tv"
    val label get() = title.title + if (isEpisode) "  S$season E$episode" else title.year?.let { " ($it)" }.orEmpty()
    val streamId: String get() {
        val imdb = title.imdb?.takeIf { Regex("tt[0-9]{5,12}").matches(it) } ?: throw SourceUnavailable("This title has no IMDb identifier for source lookup.")
        if (isEpisode && (season !in 0..999 || episode !in 1..9999)) throw SourceUnavailable("Choose an episode first.")
        return if (isEpisode) "$imdb:$season:$episode" else imdb
    }
    // Independent of torrent hash, account, file index, or playable URL.
    fun playItem() = PlayItem(if (isEpisode) ProgressKey.EPISODE else ProgressKey.MOVIE, "tmdb", title.key, title.title, season, episode, title.tmdb, title.imdb)
}

data class SourceCandidate(val hash: String, val fileIdx: Int?, val label: String, val compatible: Boolean)
data class RdSourceFile(val id: Int, val path: String, val bytes: Long, val selected: Boolean = false)

object DiscoveryPolicy {
    // Preserve meaningful country qualifiers as well as the legacy matcher form. A provider's
    // "The Office (US)" and a release named "The.Office.US" must share a policy identity.
    fun keys(name: String) = setOf(TitleMatch.key(TitleMatch.parse(name).title), TitleMatch.key(SeriesTitles.parse(name).title)).filter { it.isNotEmpty() }.toSet()
    fun allowed(name: String, blocked: Set<String>) = keys(name).none { it in blocked }
}

object TorrentioParse {
    fun sources(json: String): List<SourceCandidate> = root(json).array("streams").take(150).mapNotNull { raw ->
        val o = raw.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        if (listOf("url", "externalUrl", "ytId").any { o.has(it) }) return@mapNotNull null
        val hash = o.text("infoHash")?.lowercase()?.takeIf { Regex("[a-f0-9]{40}").matches(it) } ?: return@mapNotNull null
        val index = if (o.has("fileIdx") && !o.get("fileIdx").isJsonNull) {
            // Never silently coerce a float/string, negative index or omitted index into file zero.
            val p = o.get("fileIdx").takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@mapNotNull null
            if (!p.isNumber) return@mapNotNull null
            p.asString.takeIf { Regex("[0-9]{1,5}").matches(it) }?.toIntOrNull()?.takeIf { it <= 9999 } ?: return@mapNotNull null
        } else null
        val label = listOfNotNull(o.text("name"), o.text("title"), o.get("behaviorHints")?.takeIf { it.isJsonObject }?.asJsonObject?.text("filename")).joinToString(" · ").take(500)
        val knownCodec = Regex("(?i)\\b(x264|h[ .]?264|avc)\\b").containsMatchIn(label)
        val hd = Regex("(?i)\\b(480|576|720|1080)[pi]\\b").containsMatchIn(label)
        val unsupported = Regex("(?i)\\b(2160p|4k|8k|uhd|hdr\\w*|dv|dolby[ .]?vision|hevc|x265|h[ .]?265|av1|10[ .-]?bit|3d)\\b").containsMatchIn(label)
        SourceCandidate(hash, index, label.ifBlank { "Torrent source" }, knownCodec && hd && !unsupported)
    }.distinctBy { it.hash to it.fileIdx }.sortedWith(compareBy<SourceCandidate>({ !it.compatible }, { if (it.compatible && Regex("(?i)\\b1080[pi]\\b").containsMatchIn(it.label)) 0 else 1 })).take(40)
}

object SourceRules {
    fun select(source: SourceCandidate, target: DiscoveryTarget, files: List<RdSourceFile>, folder: String): RdSourceFile {
        if (!Regex("[a-f0-9]{40}").matches(source.hash) || files.size > 10000 || files.map { it.id }.distinct().size != files.size || files.any { it.id <= 0 }) throw SourceUnavailable("Invalid torrent file list.")
        fun matches(f: RdSourceFile): Boolean {
            if (f.bytes <= 0 || !RdNames.isVideo(f.path) || RdNames.isExtra(f.path)) return false
            val p = RdNames.parse(f.path, folder)
            if (Regex("(?i)(?:S\\d{1,2}E\\d{1,3}(?:E\\d|[-+ ]E?\\d)|\\d{1,2}x\\d{1,3}(?:x\\d|[-+]\\d))").containsMatchIn(f.path)) return false
            if (Regex("(?i)\\b(?:S\\d{1,2}E\\d{1,3}|\\d{1,2}x\\d{2})\\b").findAll(f.path).take(2).count() > 1) return false // combined episodes are not independently seekable
            return if (target.isEpisode) p.season == target.season && p.episode == target.episode && TitleMatch.same(target.title.title, target.title.year, p.title, p.year)
            else p.episode == null && TitleMatch.same(target.title.title, target.title.year, p.title, p.year)
        }
        val chosen = source.fileIdx?.let { idx ->
            if (idx !in 0..9999) throw SourceUnavailable("Invalid source file index.")
            // Torrentio's index is zero-based; RD IDs are one-based. Use the returned ID,
            // and reject reordered/missing entries rather than choosing an unrelated file.
            files.getOrNull(idx)?.takeIf { it.id == idx + 1 && matches(it) }
        } ?: if (source.fileIdx == null) files.filter(::matches).singleOrNull() else null
        return chosen ?: throw SourceUnavailable("The source does not identify one matching video file. Choose another source.")
    }
}

object DiscoveryParse {
    fun search(json: String): List<DiscoveryTitle> = root(json).array("results").take(40).mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val kind = o.text("media_type")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
        title(o, kind)
    }.distinctBy { it.key }.take(20)

    fun title(o: JsonObject, kind: String): DiscoveryTitle? {
        // Treat malformed adult flags conservatively; adult=false itself is never an age rating.
        val adult = o.get("adult")
        if (adult != null && (!adult.isJsonPrimitive || !adult.asJsonPrimitive.isBoolean || adult.asBoolean)) return null
        val id = o.text("id")?.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) } ?: return null
        val name = o.text(if (kind == "movie") "title" else "name")?.take(180) ?: return null
        val year = o.text(if (kind == "movie") "release_date" else "first_air_date")?.take(4)?.toIntOrNull()
        val seasons = o.array("seasons").take(150).mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.text("season_number")?.toIntOrNull()?.takeIf { n -> n in 0..999 } }.distinct().sorted()
        return DiscoveryTitle(kind, id, name, year, o.text("poster_path")?.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/w342$it" }, o.text("overview")?.take(1500), seasons = seasons)
    }

    fun episodes(json: String, season: Int): List<DiscoveryEpisode> = root(json).array("episodes").take(500).mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val s = o.text("season_number")?.toIntOrNull() ?: return@mapNotNull null
        val n = o.text("episode_number")?.toIntOrNull()?.takeIf { it in 1..9999 } ?: return@mapNotNull null
        if (s != season) null else DiscoveryEpisode(s, n, o.text("name")?.take(160) ?: "Episode $n")
    }.distinctBy { it.number }
}

internal fun root(json: String): JsonObject = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { throw SourceUnavailable("The service returned an invalid response.") }
internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
