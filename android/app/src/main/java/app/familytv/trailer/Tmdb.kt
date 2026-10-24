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

/** What TMDB says about a movie (the Movies spotlight, the guide header) or a show (the guide header). */
data class MovieMeta(val overview: String?, val genres: List<String>, val runtimeMin: Int?, val poster: String? = null)

/** A trending or popular film or show from TMDB, for the Home carousel. [tv] tells a show from a film. */
data class TmdbTitle(
    val id: String,
    val tv: Boolean,
    val title: String,
    val year: Int?,
    val backdrop: String?,
    val genres: List<Int>,
    val rating: Double?,
)

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

    suspend fun movie(id: String, lang: String): MovieMeta? = get("movie/$id", listOf("language" to lang))?.let(TmdbParse::movie)

    suspend fun tv(id: String, lang: String): MovieMeta? = get("tv/$id", listOf("language" to lang))?.let(TmdbParse::movie)

    suspend fun searchMovie(title: String, year: Int?): String? =
        get("search/movie", listOfNotNull("query" to title, year?.let { "year" to it.toString() }))?.let(TmdbParse::firstId)

    suspend fun searchTv(title: String, year: Int?): String? =
        get("search/tv", listOfNotNull("query" to title, year?.let { "first_air_date_year" to it.toString() }))?.let(TmdbParse::firstId)

    /** This week's top films ([tv] false) or shows, in TMDB's order. */
    suspend fun trending(tv: Boolean): List<TmdbTitle> = get("trending/${if (tv) "tv" else "movie"}/week", emptyList())?.let { TmdbParse.titles(it, tv) }.orEmpty()

    /** The most popular films or shows right now, for when few trending ones are on the line. */
    suspend fun popular(tv: Boolean, page: Int): List<TmdbTitle> =
        get("${if (tv) "tv" else "movie"}/popular", listOf("page" to page.toString()))?.let { TmdbParse.titles(it, tv) }.orEmpty()

    /** The English title logo of a film or show (a transparent image), else one with no text language. */
    suspend fun logo(tv: Boolean, id: String): String? =
        get("${if (tv) "tv" else "movie"}/$id/images", listOf("include_image_language" to "en,null"))?.let(TmdbParse::logo)

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
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        const val LOGO_BASE = "https://image.tmdb.org/t/p/w500"
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

    fun movie(o: JsonObject): MovieMeta = MovieMeta(
        overview = o.str("overview"),
        genres = o.array("genres").mapNotNull { it.obj()?.str("name") },
        runtimeMin = o.get("runtime")?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { it > 0 },
        poster = o.str("poster_path")?.let { Tmdb.POSTER_BASE + it },
    )

    /** A trending or popular list; adult titles and ones without a backdrop are left out. */
    fun titles(o: JsonObject, tv: Boolean): List<TmdbTitle> = o.array("results").mapNotNull { it.obj() }.mapNotNull { r ->
        if (r.get("adult")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return@mapNotNull null
        TmdbTitle(
            id = r.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null,
            tv = tv,
            title = r.str(if (tv) "name" else "title") ?: return@mapNotNull null,
            year = r.str(if (tv) "first_air_date" else "release_date")?.take(4)?.toIntOrNull(),
            backdrop = r.str("backdrop_path")?.let { Tmdb.BACKDROP_BASE + it } ?: return@mapNotNull null,
            genres = r.array("genre_ids").mapNotNull { g -> g.takeIf { it.isJsonPrimitive }?.asInt },
            rating = r.get("vote_average")?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf { it > 0 },
        )
    }

    /** The best-voted English logo, else the best with no text language. */
    fun logo(o: JsonObject): String? {
        val logos = o.array("logos").mapNotNull { it.obj() }.filter { it.str("file_path") != null }
        fun best(lang: String?) = logos.filter { it.str("iso_639_1") == lang }.maxByOrNull { it.get("vote_average")?.takeIf { v -> v.isJsonPrimitive }?.asDouble ?: 0.0 }
        return (best("en") ?: best(null))?.str("file_path")?.let { Tmdb.LOGO_BASE + it }
    }

    fun firstId(o: JsonObject): String? = o.array("results").firstNotNullOfOrNull { it.obj()?.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString }
}

internal fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
internal fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
