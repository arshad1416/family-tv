package app.familytv.trailer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What a metadata service knows about one episode, to fill in what the provider left out. */
data class EpisodeMeta(val still: String?, val overview: String?)

/** One season from TMDB: what it is about, its YouTube videos and its episodes' stills and overviews. */
data class SeasonMeta(val overview: String?, val videos: List<Trailers.Video>, val episodes: Map<Int, EpisodeMeta>)

/**
 * TMDB v3 client for trailers and season/episode details. [key] comes from the portal; a v4 read
 * token (a JWT) is sent as a bearer token, a v3 key as api_key.
 */
class Tmdb(private val key: String, private val client: OkHttpClient, private val base: String = BASE_URL) {

    suspend fun movieVideos(id: String, langs: List<String>): List<Trailers.Video> =
        get("movie/$id/videos", videoLangs(langs))?.let(TmdbParse::videos).orEmpty()

    suspend fun tvVideos(id: String, langs: List<String>): List<Trailers.Video> =
        get("tv/$id/videos", videoLangs(langs))?.let(TmdbParse::videos).orEmpty()

    suspend fun season(tvId: String, season: Int, langs: List<String>): SeasonMeta? =
        get("tv/$tvId/season/$season", videoLangs(langs) + ("append_to_response" to "videos") + ("language" to (langs.firstOrNull() ?: "en")))
            ?.let(TmdbParse::season)

    suspend fun searchMovie(title: String, year: Int?): String? =
        get("search/movie", listOfNotNull("query" to title, year?.let { "year" to it.toString() }))?.let(TmdbParse::firstId)

    suspend fun searchTv(title: String, year: Int?): String? =
        get("search/tv", listOfNotNull("query" to title, year?.let { "first_air_date_year" to it.toString() }))?.let(TmdbParse::firstId)

    private fun videoLangs(langs: List<String>) = listOf("include_video_language" to (langs.map { it.substringBefore('-') } + "en" + "null").distinct().joinToString(","))

    /** The JSON body, or null when TMDB doesn't have it (404). */
    private suspend fun get(path: String, params: List<Pair<String, String>>): JsonObject? = withContext(Dispatchers.IO) {
        val bearer = key.length > 60 && key.count { it == '.' } == 2
        val url = "${base.trimEnd('/')}/$path".toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", key)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        client.newCall(req).execute().use { res ->
            if (res.code == 404) return@withContext null
            if (!res.isSuccessful) throw IOException("TMDB HTTP ${res.code}")
            JsonParser.parseString(res.body?.string().orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
        }
    }

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w780"
    }
}

/** Reads TMDB responses. Pure, unit tested in TrailersTest. */
object TmdbParse {
    fun videos(o: JsonObject): List<Trailers.Video> = o.array("results").mapNotNull { e ->
        val v = e.obj() ?: return@mapNotNull null
        Trailers.Video(
            key = v.str("key") ?: return@mapNotNull null,
            site = v.str("site"),
            type = v.str("type"),
            official = v.get("official")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            language = v.str("iso_639_1"),
        )
    }

    fun season(o: JsonObject): SeasonMeta = SeasonMeta(
        overview = o.str("overview"),
        videos = o.get("videos")?.obj()?.let(::videos).orEmpty(),
        episodes = o.array("episodes").mapNotNull { it.obj() }.mapNotNull { e ->
            val n = e.get("episode_number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            n to EpisodeMeta(e.str("still_path")?.let { Tmdb.IMAGE_BASE + it }, e.str("overview"))
        }.toMap(),
    )

    fun firstId(o: JsonObject): String? = o.array("results").firstNotNullOfOrNull { it.obj()?.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString }
}

internal fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
internal fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
