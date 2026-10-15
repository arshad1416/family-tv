package app.familytv.trailer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What TVmaze says about a show or one of its episodes. */
data class MazeMeta(val summary: String?, val image: String?, val name: String? = null)

/**
 * TVmaze (no key needed): show and episode summaries and pictures for the guide header, after
 * TMDB. Its data is CC BY-SA, so wherever it is shown the header says it came from TVmaze.
 */
class TvMaze(private val client: OkHttpClient, private val base: String = BASE_URL) {

    /** The best match for [title]: its id and what the show is about. */
    suspend fun show(title: String): Pair<String, MazeMeta>? =
        get("singlesearch/shows", listOf("q" to title))?.let { o -> o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { it to TvMazeParse.meta(o) } }

    suspend fun episode(showId: String, season: Int, episode: Int): MazeMeta? =
        get("shows/$showId/episodebynumber", listOf("season" to season.toString(), "number" to episode.toString()))?.let(TvMazeParse::meta)

    /** The JSON body, or null when TVmaze has no match (404). */
    private suspend fun get(path: String, params: List<Pair<String, String>>): JsonObject? = withContext(Dispatchers.IO) {
        val url = "${base.trimEnd('/')}/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { res ->
            if (res.code == 404) return@withContext null
            if (!res.isSuccessful) throw IOException("TVmaze HTTP ${res.code}")
            JsonParser.parseString(res.body?.string().orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
        }
    }

    companion object {
        const val BASE_URL = "https://api.tvmaze.com"
    }
}

/** Reads TVmaze answers. Pure, unit tested. */
object TvMazeParse {
    fun meta(o: JsonObject) = MazeMeta(
        summary = o.str("summary")?.let(::plain),
        image = o.get("image")?.obj()?.str("medium")?.replace("http://", "https://"),
        name = o.str("name"),
    )

    /** TVmaze summaries are HTML ("<p>A <b>show</b>…</p>"); the header wants plain text. */
    fun plain(html: String): String? = html
        .replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim().ifEmpty { null }
}
