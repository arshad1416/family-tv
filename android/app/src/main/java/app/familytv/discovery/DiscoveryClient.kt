package app.familytv.discovery

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Read-only metadata and credentialless sources. This class cannot access a Real-Debrid account. */
class DiscoveryClient(
    client: OkHttpClient,
    private val tmdbKey: String,
    private val tmdbBase: String = "https://api.themoviedb.org/3",
    private val torrentioBase: String = "https://torrentio.strem.fun",
) {
    private val http = DiscoveryHttp(client)
    suspend fun search(query: String): List<DiscoveryTitle> {
        val q = query.trim().take(120)
        if (q.length < 2) return emptyList()
        return DiscoveryParse.search(tmdb("search/multi", listOf("query" to q, "include_adult" to "false", "page" to "1")))
    }

    suspend fun details(title: DiscoveryTitle): DiscoveryTitle {
        require(title.kind in setOf("movie", "tv") && Regex("[1-9][0-9]{0,9}").matches(title.tmdb))
        val o = root(tmdb("${title.kind}/${title.tmdb}", listOf("append_to_response" to "external_ids")))
        val found = DiscoveryParse.title(o, title.kind) ?: throw SourceUnavailable("This title is unavailable for discovery.")
        val imdb = o.get("external_ids")?.takeIf { it.isJsonObject }?.asJsonObject?.text("imdb_id")
            ?.takeIf { Regex("tt[0-9]{5,12}").matches(it) }
        return found.copy(imdb = imdb)
    }

    suspend fun episodes(title: DiscoveryTitle, season: Int): List<DiscoveryEpisode> {
        require(title.kind == "tv" && season in title.seasons && Regex("[1-9][0-9]{0,9}").matches(title.tmdb))
        return DiscoveryParse.episodes(tmdb("tv/${title.tmdb}/season/$season"), season)
    }

    suspend fun sources(target: DiscoveryTarget): List<SourceCandidate> {
        val id = target.streamId
        val type = if (target.isEpisode) "series" else "movie"
        // No debrid configuration, API key, headers, tracking sources or magnet URLs are accepted.
        val url = torrentioBase.toHttpUrl().newBuilder().addPathSegment("stream").addPathSegment(type).addPathSegment("$id.json").build()
        val now = System.currentTimeMillis()
        if (now < (cooldowns[torrentioBase] ?: 0)) throw SourceUnavailable("Torrentio is busy. Wait a minute before trying again.")
        val (status, body) = http.request(Request.Builder().url(url).header("Accept", "application/json").build(), "Torrentio")
        if (status == 429) cooldowns[torrentioBase] = System.currentTimeMillis() + 60000
        if (status !in 200..299) throw SourceUnavailable(if (status == 429) "Torrentio is busy. Wait before trying again." else "Torrentio is unavailable. Try again later.")
        return TorrentioParse.sources(body)
    }

    companion object { private val cooldowns = ConcurrentHashMap<String, Long>() }

    private suspend fun tmdb(path: String, params: List<Pair<String, String>> = emptyList()): String {
        if (tmdbKey.isBlank()) throw SourceUnavailable("TMDB is not configured. Ask your TV admin to enable metadata.")
        val bearer = tmdbKey.length > 60 && tmdbKey.count { it == '.' } == 2
        val url = "$tmdbBase/$path".toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", tmdbKey)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply { if (bearer) header("Authorization", "Bearer $tmdbKey") }.build()
        val (status, body) = http.request(request, "TMDB")
        if (status !in 200..299) throw SourceUnavailable("TMDB is unavailable (HTTP $status). Try again later.")
        return body
    }
}
