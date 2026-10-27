package app.familytv.trailer

import app.familytv.data.Popularity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TMDB's most-voted films and shows ([PAGES] pages of 20 each), kept in [file] and fetched again
 * once a week, to order search results by how well known the titles are
 * ([app.familytv.data.PopularOrder]). Only these lists come from TMDB: nothing typed goes to it.
 */
class MostVoted(private val file: File) {
    class Lists(val at: Long, val movies: Popularity, val shows: Popularity) {
        fun of(tv: Boolean) = if (tv) shows else movies
    }

    private val lock = Mutex()
    private var read = false
    private val _lists = MutableStateFlow<Lists?>(null)

    /** The lists once read or fetched; null before, or with none kept. */
    val lists: StateFlow<Lists?> = _lists

    /** The kept lists, read from disk on first use. */
    suspend fun ready(): Lists? = lock.withLock {
        if (!read) {
            read = true
            _lists.value = withContext(Dispatchers.IO) { runCatching { parse(file.readText()) }.getOrNull() }
        }
        _lists.value
    }

    /** Fetches the lists when the kept ones are [MAX_AGE_MS] old or missing; a failed fetch keeps them as they are. */
    suspend fun refreshIfDue(tmdb: Tmdb, now: Long = System.currentTimeMillis()) {
        ready()?.let { if (now - it.at in 0 until MAX_AGE_MS) return }
        val movies = fetch(tmdb, tv = false) ?: return
        val shows = fetch(tmdb, tv = true) ?: return
        val text = JsonObject().apply {
            addProperty("at", now)
            add("movies", json(movies))
            add("shows", json(shows))
        }.toString()
        withContext(Dispatchers.IO) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
        lock.withLock { _lists.value = Lists(now, Popularity(movies), Popularity(shows)) }
    }

    private suspend fun fetch(tmdb: Tmdb, tv: Boolean): List<TmdbVotes>? {
        val all = mutableListOf<TmdbVotes>()
        for (page in 1..PAGES) {
            val got = runCatching { tmdb.mostVoted(tv, page) }.getOrElse { return null }
            if (got.isEmpty()) break
            all += got
        }
        return all.takeIf { it.isNotEmpty() }
    }

    private fun json(titles: List<TmdbVotes>) = JsonArray().apply {
        titles.forEach { t -> add(JsonArray().apply { add(t.id); add(t.title); add(t.year); add(t.votes) }) }
    }

    private fun parse(text: String): Lists {
        val o = JsonParser.parseString(text).asJsonObject
        fun titles(name: String) = o.getAsJsonArray(name).map { e ->
            val a = e.asJsonArray
            TmdbVotes(a[0].asString, a[1].asString, a[2].takeIf { !it.isJsonNull }?.asInt, a[3].asInt)
        }
        return Lists(o.get("at").asLong, Popularity(titles("movies")), Popularity(titles("shows")))
    }

    companion object {
        /** 1,000 of each. */
        const val PAGES = 50
        const val MAX_AGE_MS = 7 * 24 * 3_600_000L
    }
}
