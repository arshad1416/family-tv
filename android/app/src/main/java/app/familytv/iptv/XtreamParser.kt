package app.familytv.iptv

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

data class XtCategory(val id: String, val name: String)

data class XtStream(
    val id: String,
    val name: String,
    val num: Int,
    val icon: String?,
    val categoryId: String,
    val epgId: String?,
    val catchupDays: Int,
    val ext: String?,
    val rating: String?,
    val added: Long,
    val plot: String?,
    /** TMDB id when the panel lists it (movies and series); lets Trakt match without a title search. */
    val tmdb: String? = null,
    val year: Int? = null,
)

/** Ids from get_vod_info / get_series_info, for matching against Trakt. */
data class XtInfoIds(val tmdb: String?, val imdb: String?, val year: Int?)

data class XtAccount(
    val auth: Boolean,
    val status: String?,
    val expiresAt: Long?, // epoch millis, null = never
    val activeConnections: Int?,
    val maxConnections: Int?,
    val serverTimezone: String?,
)

/**
 * Streaming readers for the Xtream Codes player_api.php responses. Panels are inconsistent
 * (numbers arrive as strings, nulls, or "" and lists sometimes as objects), so every field
 * is read leniently.
 */
object XtreamParser {

    fun categories(reader: Reader): List<XtCategory> {
        val out = ArrayList<XtCategory>()
        eachObject(reader) { m ->
            val id = m["category_id"] ?: return@eachObject
            out += XtCategory(id, m["category_name"].orEmpty().ifBlank { "Other" })
        }
        return out
    }

    /** Works for get_live_streams, get_vod_streams and get_series (series use series_id/cover). */
    fun streams(reader: Reader, onStream: (XtStream) -> Unit) {
        var index = 0
        eachObject(reader) { m ->
            val id = m["stream_id"] ?: m["series_id"] ?: return@eachObject
            index++
            onStream(
                XtStream(
                    id = id,
                    name = m["name"].orEmpty().trim(),
                    num = m["num"]?.toDoubleOrNull()?.toInt() ?: index,
                    icon = (m["stream_icon"] ?: m["cover"])?.takeIf { it.isNotBlank() },
                    categoryId = m["category_id"].orEmpty(),
                    epgId = m["epg_channel_id"]?.takeIf { it.isNotBlank() },
                    catchupDays = if (m["tv_archive"] == "1") m["tv_archive_duration"]?.toIntOrNull() ?: 0 else 0,
                    ext = m["container_extension"]?.takeIf { it.isNotBlank() },
                    rating = m["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                    added = (m["added"] ?: m["last_modified"])?.toLongOrNull()?.times(1000) ?: 0L,
                    plot = m["plot"]?.takeIf { it.isNotBlank() },
                    tmdb = tmdbId(m["tmdb"] ?: m["tmdb_id"]),
                    year = yearOf(m["year"] ?: m["releaseDate"] ?: m["release_date"] ?: m["releasedate"]),
                ),
            )
        }
    }

    fun account(reader: Reader): XtAccount {
        val r = JsonReader(reader).apply { isLenient = true }
        var acc = XtAccount(false, null, null, null, null, null)
        var tz: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "user_info" -> {
                    val m = readFlatObject(r)
                    acc = XtAccount(
                        auth = m["auth"] == "1" || m["auth"] == "true",
                        status = m["status"],
                        expiresAt = m["exp_date"]?.toLongOrNull()?.times(1000),
                        activeConnections = m["active_cons"]?.toIntOrNull(),
                        maxConnections = m["max_connections"]?.toIntOrNull(),
                        serverTimezone = null,
                    )
                }
                "server_info" -> tz = readFlatObject(r)["timezone"]
                else -> r.skipValue()
            }
        }
        return acc.copy(serverTimezone = tz)
    }

    data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val ext: String?, val plot: String?, val image: String?)

    /** get_series_info: "episodes" is an object keyed by season number, each a list of episodes. */
    fun episodes(reader: Reader): List<Episode> {
        val out = ArrayList<Episode>()
        val r = JsonReader(reader).apply { isLenient = true }
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return out }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "episodes" || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            r.beginObject()
            while (r.hasNext()) {
                val season = r.nextName().toIntOrNull() ?: 0
                if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var id: String? = null; var num = 0; var title = ""; var ext: String? = null; var plot: String? = null; var image: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "id" -> id = scalar(r)
                            "episode_num" -> num = scalar(r)?.toIntOrNull() ?: 0
                            "title" -> title = scalar(r).orEmpty()
                            "container_extension" -> ext = scalar(r)
                            "info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                val info = readFlatObject(r); plot = info["plot"]; image = info["movie_image"]
                            } else r.skipValue()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id != null) out += Episode(id, season, num, title, ext, plot, image)
                }
                r.endArray()
            }
            r.endObject()
        }
        r.endObject()
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** get_vod_info / get_series_info: the ids live in the top-level "info" object. */
    fun infoIds(reader: Reader): XtInfoIds {
        val r = JsonReader(reader).apply { isLenient = true }
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return XtInfoIds(null, null, null) }
        var info: Map<String, String?> = emptyMap()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "info" && r.peek() == JsonToken.BEGIN_OBJECT) info = readFlatObject(r) else r.skipValue()
        }
        r.endObject()
        val imdb = (info["imdb_id"] ?: info["imdb"])?.trim()?.takeIf { IMDB.matches(it) }
        return XtInfoIds(
            tmdb = tmdbId(info["tmdb_id"] ?: info["tmdb"]),
            imdb = imdb,
            year = yearOf(info["year"] ?: info["releasedate"] ?: info["releaseDate"] ?: info["release_date"]),
        )
    }

    private val IMDB = Regex("tt\\d{5,10}")
    private val YEAR = Regex("(19|20)\\d{2}")

    /** Panels send "0", "", "null" or a float for a missing id. */
    internal fun tmdbId(v: String?): String? =
        v?.trim()?.substringBefore('.')?.takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } && id.trimStart('0').isNotEmpty() }

    internal fun yearOf(v: String?): Int? = v?.let { YEAR.find(it)?.value?.toInt() }

    private fun eachObject(reader: Reader, onObject: (Map<String, String?>) -> Unit) {
        val r = JsonReader(reader).apply { isLenient = true }
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readFlatObject(r)) else r.skipValue()
                r.endArray()
            }
            // Some panels return {"1": {...}, "2": {...}} instead of a list.
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) { r.nextName(); if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readFlatObject(r)) else r.skipValue() }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }

    /** Reads one object keeping scalar fields as strings and skipping nested values. */
    private fun readFlatObject(r: JsonReader): Map<String, String?> {
        val m = HashMap<String, String?>(24)
        r.beginObject()
        while (r.hasNext()) m[r.nextName()] = scalar(r)
        r.endObject()
        return m
    }

    private fun scalar(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }
}
