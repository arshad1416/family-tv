package app.familytv.iptv

import android.util.Xml
import app.familytv.core.Http
import app.familytv.data.AppDb
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.ProgrammeEntity
import app.familytv.data.SeriesEntity
import app.familytv.data.SourceEntity
import app.familytv.portal.Profile
import app.familytv.portal.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.Reader
import java.util.zip.GZIPInputStream

/**
 * Keeps the local catalog (channels, movies, series, guide) in step with the profile's sources.
 * Lists are streamed straight from the network into SQLite in batches, so memory stays flat
 * no matter how big the provider's lists are.
 */
class Catalog(private val db: AppDb) {
    private val dao = db.catalog()

    data class Progress(val step: String)

    suspend fun refresh(profile: Profile, onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        dao.dropSourcesExcept(profile.sources.map { it.id }.ifEmpty { listOf("") })
        dao.dropOrphanChannels(); dao.dropOrphanMovies(); dao.dropOrphanSeries(); dao.dropOrphanCategories()
        val errors = mutableListOf<String>()
        for (s in profile.sources) {
            try {
                when (s.type) {
                    "xtream" -> refreshXtream(s, onProgress)
                    "m3u" -> refreshM3u(s, onProgress)
                }
            } catch (e: IOException) {
                errors += "${s.name}: ${e.message}"
            }
        }
        val now = System.currentTimeMillis()
        dao.pruneProgrammes(before = now - 2 * HOUR, after = now + GUIDE_HOURS * HOUR)
        if (errors.isNotEmpty() && errors.size == profile.sources.size) throw IOException(errors.joinToString("; "))
    }

    /** Picks the first server URL that answers with a valid login, so backup URLs just work. */
    suspend fun activeServer(s: Source): Pair<String, XtAccount> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (server in s.serverUrls.orEmpty()) {
            try {
                val acc = get(XtreamUrls.api(server, s.username!!, s.password.orEmpty())) { XtreamParser.account(it) }
                return@withContext server.trimEnd('/') to acc
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("no server answered for ${s.name}", last)
    }

    private suspend fun refreshXtream(s: Source, onProgress: (Progress) -> Unit) {
        val user = s.username!!
        val pass = s.password.orEmpty()
        val (server, account) = activeServer(s)
        if (!account.auth) throw IOException("login rejected")
        val gen = System.currentTimeMillis()
        dao.upsertSource(SourceEntity(s.id, s.name, s.type, server, gen, gen))

        for ((kind, catAction) in listOf(
            Kind.LIVE to "get_live_categories",
            Kind.MOVIE to "get_vod_categories",
            Kind.SERIES to "get_series_categories",
        )) {
            val cats = get(XtreamUrls.api(server, user, pass, catAction)) { XtreamParser.categories(it) }
            dao.upsertCategories(cats.mapIndexed { i, c -> CategoryEntity(s.id, kind, c.id, c.name, i, gen) })
            dao.pruneCategories(s.id, kind, gen)
        }

        onProgress(Progress("Loading channels"))
        streamBatches(XtreamUrls.api(server, user, pass, "get_live_streams"), { dao.upsertChannels(it) }) { st ->
            ChannelEntity(s.id, st.id, st.name, st.num, st.icon, st.categoryId, st.epgId, null, st.catchupDays, gen)
        }
        dao.pruneChannels(s.id, gen)

        onProgress(Progress("Loading movies"))
        streamBatches(XtreamUrls.api(server, user, pass, "get_vod_streams"), { dao.upsertMovies(it) }) { st ->
            MovieEntity(s.id, st.id, st.name, st.icon, st.categoryId, st.rating, st.ext, null, st.added, gen)
        }
        dao.pruneMovies(s.id, gen)

        onProgress(Progress("Loading series"))
        streamBatches(XtreamUrls.api(server, user, pass, "get_series"), { dao.upsertSeries(it) }) { st ->
            SeriesEntity(s.id, st.id, st.name, st.icon, st.categoryId, st.plot, st.rating, gen)
        }
        dao.pruneSeries(s.id, gen)

        onProgress(Progress("Loading guide"))
        loadGuide(s.id, s.epgUrl ?: XtreamUrls.xmltv(server, user, pass))
    }

    private suspend fun refreshM3u(s: Source, onProgress: (Progress) -> Unit) {
        val gen = System.currentTimeMillis()
        dao.upsertSource(SourceEntity(s.id, s.name, s.type, null, gen, gen))
        onProgress(Progress("Loading playlist"))
        val liveCats = LinkedHashMap<String, Int>()
        val movieCats = LinkedHashMap<String, Int>()
        val channels = ArrayList<ChannelEntity>(BATCH)
        val movies = ArrayList<MovieEntity>(BATCH)
        var n = 0
        open(s.m3uUrl!!).use { res ->
            M3uParser.parse(reader(res)) { e ->
                n++
                // Xtream-style M3U exports put VOD under /movie/ and /series/ paths.
                val isVod = e.url.contains("/movie/") || e.url.contains("/series/")
                val id = e.url.hashCode().toUInt().toString(16)
                if (isVod) {
                    movieCats.getOrPut(e.group) { movieCats.size }
                    movies += MovieEntity(s.id, id, e.name, e.logo, e.group, null, null, e.url, 0, gen)
                    if (movies.size >= BATCH) { runBlocking { dao.upsertMovies(movies.toList()) }; movies.clear() }
                } else {
                    liveCats.getOrPut(e.group) { liveCats.size }
                    channels += ChannelEntity(s.id, id, e.name, e.chno ?: n, e.logo, e.group, e.tvgId, e.url, e.catchupDays, gen)
                    if (channels.size >= BATCH) { runBlocking { dao.upsertChannels(channels.toList()) }; channels.clear() }
                }
            }
        }
        if (channels.isNotEmpty()) dao.upsertChannels(channels)
        if (movies.isNotEmpty()) dao.upsertMovies(movies)
        dao.upsertCategories(liveCats.map { (name, i) -> CategoryEntity(s.id, Kind.LIVE, name, name, i, gen) })
        dao.upsertCategories(movieCats.map { (name, i) -> CategoryEntity(s.id, Kind.MOVIE, name, name, i, gen) })
        for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) dao.pruneCategories(s.id, k, gen)
        dao.pruneChannels(s.id, gen)
        dao.pruneMovies(s.id, gen)
        s.epgUrl?.let { onProgress(Progress("Loading guide")); loadGuide(s.id, it) }
    }

    /** Guide failures never fail the refresh: channels still play without a guide. */
    private suspend fun loadGuide(sourceId: String, url: String) {
        val wanted = HashSet<String>()
        var offset = 0
        while (true) {
            val page = dao.allChannels(2000, offset)
            if (page.isEmpty()) break
            page.forEach { c -> if (c.sourceId == sourceId) c.epgId?.let(wanted::add) }
            offset += page.size
        }
        if (wanted.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            open(url).use { res ->
                val input = res.body!!.byteStream().let { if (isGzip(url, res)) GZIPInputStream(it, 64 * 1024) else it }
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                dao.clearProgrammesFor(sourceId)
                val pending = ArrayList<ProgrammeEntity>(BATCH)
                XmltvParser.parse(parser, now - 2 * HOUR, now + GUIDE_HOURS * HOUR, wanted) { p ->
                    pending += ProgrammeEntity(epgId = p.channel, start = p.start, stop = p.stop, title = p.title, description = p.description)
                    if (pending.size >= BATCH) { val chunk = pending.toList(); pending.clear(); runBlocking { dao.insertProgrammes(chunk) } }
                }
                if (pending.isNotEmpty()) dao.insertProgrammes(pending)
            }
        } catch (e: Exception) {
            android.util.Log.w("Catalog", "guide load failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun episodes(sourceId: String, seriesId: String, s: Source): List<XtreamParser.Episode> = withContext(Dispatchers.IO) {
        val server = dao.source(sourceId)?.activeServer ?: s.serverUrls!!.first()
        get(XtreamUrls.api(server, s.username!!, s.password.orEmpty(), "get_series_info", mapOf("series_id" to seriesId))) { XtreamParser.episodes(it) }
    }

    // --- streaming helpers ---

    /** Streams an Xtream list and writes it [BATCH] rows at a time (runs on an IO thread). */
    private suspend fun <T> streamBatches(url: String, write: suspend (List<T>) -> Unit, map: (XtStream) -> T) {
        val buf = ArrayList<T>(BATCH)
        open(url).use { res ->
            XtreamParser.streams(reader(res)) { st ->
                buf += map(st)
                if (buf.size >= BATCH) { val chunk = buf.toList(); buf.clear(); runBlocking { write(chunk) } }
            }
        }
        if (buf.isNotEmpty()) write(buf)
    }

    private fun open(url: String): Response {
        val res = Http.client.newCall(Request.Builder().url(url).build()).execute()
        if (!res.isSuccessful) { res.close(); throw IOException("HTTP ${res.code}") }
        return res
    }

    private fun <T> get(url: String, parse: (Reader) -> T): T = open(url).use { parse(reader(it)) }

    private fun reader(res: Response): Reader = res.body!!.charStream().buffered(64 * 1024)

    private fun isGzip(url: String, res: Response): Boolean {
        if (url.substringBefore('?').endsWith(".gz")) return true
        val type = res.header("Content-Type").orEmpty()
        return type.contains("gzip") && res.header("Content-Encoding") == null
    }

    companion object {
        const val BATCH = 500
        const val HOUR = 3_600_000L
        const val GUIDE_HOURS = 48L
    }
}
