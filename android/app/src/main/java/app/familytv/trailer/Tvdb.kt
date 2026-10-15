package app.familytv.trailer

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/**
 * TheTVDB v4, the last fallback for series (after the provider and TMDB): episode stills and
 * overviews, and the show's trailer. Logs in with the portal's key; the bearer token lasts about a
 * month, so it is kept in [prefs] and renewed on a 401.
 */
class Tvdb(
    private val key: String,
    private val client: OkHttpClient,
    private val prefs: SharedPreferences?,
    private val base: String = BASE_URL,
) {
    @Volatile private var token: String? = null

    /** TVDB series id: by IMDb or TMDB id where TVDB knows it, else by title and year. */
    suspend fun seriesId(tmdb: String?, imdb: String?, title: String, year: Int?): String? {
        for (remote in listOfNotNull(imdb, tmdb)) get("search/remoteid/$remote")?.let(TvdbParse::remoteSeriesId)?.let { return it }
        return get("search", listOfNotNull("query" to title, "type" to "series", year?.let { "year" to it.toString() }))?.let(TvdbParse::searchId)
    }

    /** Episode stills and overviews by (season, episode), from the default order. */
    suspend fun episodes(seriesId: String): Map<Pair<Int, Int>, EpisodeMeta> {
        val out = HashMap<Pair<Int, Int>, EpisodeMeta>()
        for (page in 0 until MAX_PAGES) {
            val o = get("series/$seriesId/episodes/default", listOf("page" to page.toString())) ?: break
            out += TvdbParse.episodes(o)
            if (!TvdbParse.hasNext(o)) break
        }
        return out
    }

    suspend fun trailer(seriesId: String, langs: List<String>): String? =
        get("series/$seriesId/extended", listOf("short" to "true"))?.let { TvdbParse.trailer(it, langs) }

    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JsonObject? = withContext(Dispatchers.IO) {
        val url = "${base.trimEnd('/')}/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        for (attempt in 0..1) {
            val t = token(renew = attempt > 0)
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $t").header("Accept", "application/json").build()).execute().use { res ->
                when {
                    res.code == 401 && attempt == 0 -> Unit // token expired: log in again once
                    res.code == 404 -> return@withContext null
                    !res.isSuccessful -> throw IOException("TVDB HTTP ${res.code}")
                    else -> return@withContext JsonParser.parseString(res.body?.string().orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
                }
            }
        }
        throw IOException("TVDB login rejected")
    }

    private fun token(renew: Boolean): String {
        if (!renew) {
            token?.let { return it }
            val saved = prefs?.getString(PREF_TOKEN, null)
            val at = prefs?.getLong(PREF_AT, 0L) ?: 0L
            if (saved != null && prefs?.getString(PREF_KEY, null) == key.hashCode().toString() && System.currentTimeMillis() - at < TOKEN_DAYS * DAY) {
                token = saved
                return saved
            }
        }
        val body = """{"apikey":${com.google.gson.Gson().toJson(key)}}""".toRequestBody("application/json".toMediaType())
        val fresh = client.newCall(Request.Builder().url("${base.trimEnd('/')}/login").post(body).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("TVDB login HTTP ${res.code}")
            TvdbParse.token(JsonParser.parseString(res.body?.string().orEmpty()).asJsonObject) ?: throw IOException("TVDB login returned no token")
        }
        token = fresh
        prefs?.edit()?.putString(PREF_TOKEN, fresh)?.putLong(PREF_AT, System.currentTimeMillis())?.putString(PREF_KEY, key.hashCode().toString())?.apply()
        return fresh
    }

    companion object {
        const val BASE_URL = "https://api4.thetvdb.com/v4"
        private const val ARTWORK = "https://artworks.thetvdb.com"
        private const val MAX_PAGES = 4
        private const val TOKEN_DAYS = 25L
        private const val DAY = 86_400_000L
        private const val PREF_TOKEN = "tvdb.token"
        private const val PREF_AT = "tvdb.tokenAt"
        private const val PREF_KEY = "tvdb.key"

        internal fun image(path: String?): String? = when {
            path.isNullOrBlank() -> null
            path.startsWith("http") -> path
            else -> ARTWORK + (if (path.startsWith("/")) path else "/$path")
        }
    }
}

/** Reads TheTVDB v4 responses. Pure, unit tested in TrailersTest. */
object TvdbParse {
    fun token(o: JsonObject): String? = o.get("data")?.obj()?.str("token")

    fun remoteSeriesId(o: JsonObject): String? = o.array("data").firstNotNullOfOrNull { it.obj()?.get("series")?.obj()?.str("id") }

    fun searchId(o: JsonObject): String? = o.array("data").firstNotNullOfOrNull { it.obj()?.let { r -> r.str("tvdb_id") ?: r.str("id")?.removePrefix("series-") } }

    fun episodes(o: JsonObject): Map<Pair<Int, Int>, EpisodeMeta> =
        o.get("data")?.obj()?.array("episodes").orEmpty().mapNotNull { it.obj() }.mapNotNull { e ->
            val s = e.get("seasonNumber")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val n = e.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            (s to n) to EpisodeMeta(Tvdb.image(e.str("image")), e.str("overview"))
        }.toMap()

    fun hasNext(o: JsonObject): Boolean = o.get("links")?.obj()?.str("next") != null

    /** The YouTube trailer in the most preferred language (TVDB uses 3-letter codes), else the first. */
    fun trailer(o: JsonObject, langs: List<String>): String? {
        val all = o.get("data")?.obj()?.array("trailers").orEmpty().mapNotNull { it.obj() }
            .mapNotNull { t -> Trailers.youtubeId(t.str("url"))?.let { it to t.str("language") } }
        val want = langs.mapNotNull { l -> runCatching { Locale(l.substringBefore('-')).isO3Language }.getOrNull() } + "eng"
        return all.minByOrNull { (_, lang) -> want.indexOf(lang).let { if (it < 0) want.size else it } }?.first
    }
}
