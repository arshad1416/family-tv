package app.familytv.iptv

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Xml
import app.familytv.core.Http
import app.familytv.data.AppDb
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.Names
import app.familytv.data.ProgrammeEntity
import app.familytv.data.SeriesEntity
import app.familytv.data.SourceEntity
import app.familytv.perf.Perf
import app.familytv.portal.Profile
import app.familytv.portal.Source
import app.familytv.sync.PlaybackStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Keeps the local catalog (channels, movies, series, guide) in step with the profile's sources.
 * Lists are streamed straight from the network into SQLite in batches, so memory stays flat
 * no matter how big the provider's lists are.
 */
class Catalog(private val db: AppDb, private val marks: SharedPreferences? = null) {
    private val dao = db.catalog()

    /** [fraction] is 0..1 across the whole refresh; [detail] is a live count such as "12,400 channels". */
    data class Progress(val step: String, val fraction: Float, val detail: String? = null)

    /**
     * Turns per-step progress into one overall fraction. Providers rarely say how long a list is
     * (gzip drops Content-Length), so inside a list step the bar eases towards 90% of that step as
     * rows arrive and jumps to the end when the list finishes; the row count shown is exact.
     */
    private class Tracker(private val sources: Int, private val report: (Progress) -> Unit) {
        private var done = 0f
        private var weight = 0f
        private var step = ""

        fun startSource(index: Int) { done = index.toFloat(); weight = 0f }
        fun step(name: String, share: Float) {
            done += weight
            step = name; weight = share
            emit(0f, null)
        }
        fun rows(n: Int, typical: Int, noun: String) =
            emit(0.9f * (1f - kotlin.math.exp(-n.toFloat() / typical)), "%,d %s".format(n, noun))
        private fun emit(within: Float, detail: String?) =
            report(Progress(step, ((done + weight * within) / sources.coerceAtLeast(1)).coerceIn(0f, 1f), detail))
    }

    /**
     * False from the start of a refresh until it finishes, extra guides and all: a refresh cut off
     * (the app closed during the first load) leaves lists without their extra guides, and the next
     * start loads them again. False on a TV that hasn't recorded one yet, and after an update that
     * changes how lists are stored or matched ([FORMAT]): an update over an existing install keeps
     * its lists, which only pick up new search text and guide links when they are loaded again.
     */
    val complete: Boolean get() = marks?.let { it.getBoolean(COMPLETE, false) && it.getInt(FORMAT_KEY, 0) == FORMAT } ?: true

    suspend fun refresh(profile: Profile, onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        marks?.edit()?.putBoolean(COMPLETE, false)?.apply()
        dao.dropSourcesExcept(profile.sources.map { it.id }.ifEmpty { listOf("") })
        dao.dropOrphanChannels(); dao.dropOrphanMovies(); dao.dropOrphanSeries(); dao.dropOrphanCategories()
        val errors = mutableListOf<String>()
        val extras = profile.extraGuides.orEmpty().distinct().take(MAX_EXTRA_GUIDES)
        val t = Tracker(profile.sources.size + (if (extras.isEmpty()) 0 else 1), onProgress)
        val began = SystemClock.elapsedRealtime()
        for ((i, s) in profile.sources.withIndex()) {
            t.startSource(i)
            try {
                when (s.type) {
                    "xtream" -> refreshXtream(s, t)
                    "m3u" -> refreshM3u(s, t)
                }
            } catch (e: IOException) {
                errors += "${s.name}: ${e.message}"
            }
        }
        // The lists alone (the extra guides load after); the Rust app measures the same span.
        if (profile.sources.isNotEmpty() && errors.size < profile.sources.size) Perf.listLoaded(SystemClock.elapsedRealtime() - began)
        t.startSource(profile.sources.size)
        loadExtraGuides(profile, extras, t)
        onProgress(Progress("Finishing", 1f))
        val now = System.currentTimeMillis()
        dao.pruneProgrammes(before = now - 2 * HOUR, after = now + GUIDE_HOURS * HOUR)
        if (errors.isNotEmpty() && errors.size == profile.sources.size) throw IOException(errors.joinToString("; "))
        marks?.edit()?.putBoolean(COMPLETE, true)?.putInt(FORMAT_KEY, FORMAT)?.apply()
    }

    /**
     * Picks the first server URL that answers with a valid login, so backup URLs just work. A server
     * this TV switched to after stream failures goes first ([ServerSwitch]).
     */
    suspend fun activeServer(s: Source): Pair<String, XtAccount> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (server in (listOfNotNull(ServerSwitch.chosen(s)) + s.serverUrls.orEmpty()).distinctBy { it.trimEnd('/') }) {
            try {
                val acc = get(XtreamUrls.api(server, s.username!!, s.password.orEmpty())) { XtreamParser.account(it) }
                return@withContext server.trimEnd('/') to acc
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("no server answered for ${s.name}", last)
    }

    private suspend fun refreshXtream(s: Source, t: Tracker) {
        t.step("Signing in", 0.05f)
        val user = s.username!!
        val pass = s.password.orEmpty()
        val (server, account) = activeServer(s)
        if (!account.auth) throw IOException("login rejected")
        val gen = System.currentTimeMillis()
        dao.upsertSource(SourceEntity(s.id, s.name, s.type, server, gen, gen))

        var movieCats: Map<String, String> = emptyMap()
        var seriesCats: Map<String, String> = emptyMap()
        for ((kind, catAction) in listOf(
            Kind.LIVE to "get_live_categories",
            Kind.MOVIE to "get_vod_categories",
            Kind.SERIES to "get_series_categories",
        )) {
            val cats = get(XtreamUrls.api(server, user, pass, catAction)) { XtreamParser.categories(it) }
            dao.upsertCategories(cats.mapIndexed { i, c -> CategoryEntity(s.id, kind, c.id, c.name, i, gen) })
            dao.pruneCategories(s.id, kind, gen)
            if (kind == Kind.MOVIE) movieCats = cats.associate { it.id to it.name }
            if (kind == Kind.SERIES) seriesCats = cats.associate { it.id to it.name }
        }

        t.step("Loading channels", 0.2f)
        streamBatches(XtreamUrls.api(server, user, pass, "get_live_streams"), { dao.upsertChannels(it) }, { t.rows(it, 5_000, "channels") }) { st ->
            // Heading rows ("##### 4K #####") aren't channels; leave them out of every list.
            if (Names.isSeparator(st.name)) null
            else ChannelEntity(s.id, st.id, st.name, st.num, st.icon, st.categoryId, st.epgId, null, st.catchupDays, gen)
        }
        dao.pruneChannels(s.id, gen)

        t.step("Loading movies", 0.35f)
        streamBatches(XtreamUrls.api(server, user, pass, "get_vod_streams"), { dao.upsertMovies(it) }, { t.rows(it, 20_000, "movies") }) { st ->
            val parsed = SeriesTitles.parse(st.name)
            MovieEntity(
                s.id, st.id, st.name, st.icon, st.categoryId, st.rating, st.ext, null, st.added, gen, st.tmdb, st.year,
                titleKey = parsed.key, lang = parsed.lang ?: SeriesTitles.fromCategory(movieCats[st.categoryId]) ?: SeriesTitles.ORIGINAL,
            )
        }
        dao.pruneMovies(s.id, gen)

        t.step("Loading series", 0.2f)
        streamBatches(XtreamUrls.api(server, user, pass, "get_series"), { dao.upsertSeries(it) }, { t.rows(it, 5_000, "series") }) { st ->
            val t = SeriesTitles.parse(st.name)
            SeriesEntity(
                s.id, st.id, st.name, st.icon, st.categoryId, st.plot, st.rating, gen, st.tmdb, st.year,
                titleKey = t.key, lang = t.lang ?: SeriesTitles.fromCategory(seriesCats[st.categoryId]) ?: SeriesTitles.ORIGINAL,
            )
        }
        dao.pruneSeries(s.id, gen)

        t.step("Loading guide", 0.2f)
        loadGuide(s.id, s.epgUrl ?: XtreamUrls.xmltv(server, user, pass), t)
    }

    private suspend fun refreshM3u(s: Source, t: Tracker) {
        val gen = System.currentTimeMillis()
        dao.upsertSource(SourceEntity(s.id, s.name, s.type, null, gen, gen))
        t.step("Loading playlist", if (s.epgUrl != null) 0.8f else 1f)
        val liveCats = LinkedHashMap<String, Int>()
        val movieCats = LinkedHashMap<String, Int>()
        val channels = ArrayList<ChannelEntity>(BATCH)
        val movies = ArrayList<MovieEntity>(BATCH)
        var n = 0
        open(s.m3uUrl!!).use { res ->
            M3uParser.parse(reader(res)) { e ->
                n++
                if (n % BATCH == 0) t.rows(n, 20_000, "items")
                // Xtream-style M3U exports put VOD under /movie/ and /series/ paths.
                val isVod = e.url.contains("/movie/") || e.url.contains("/series/")
                val id = e.url.hashCode().toUInt().toString(16)
                if (isVod) {
                    movieCats.getOrPut(e.group) { movieCats.size }
                    val parsed = SeriesTitles.parse(e.name)
                    movies += MovieEntity(
                        s.id, id, e.name, e.logo, e.group, null, null, e.url, 0, gen,
                        titleKey = parsed.key, lang = parsed.lang ?: SeriesTitles.fromCategory(e.group) ?: SeriesTitles.ORIGINAL,
                    )
                    if (movies.size >= BATCH) { runBlocking { dao.upsertMovies(movies.toList()) }; movies.clear() }
                } else if (!Names.isSeparator(e.name)) {
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
        s.epgUrl?.let { t.step("Loading guide", 0.2f); loadGuide(s.id, it, t) }
    }

    /** Guide failures never fail the refresh: channels still play without a guide. */
    private suspend fun loadGuide(sourceId: String, url: String, t: Tracker) {
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
                var kept = 0
                XmltvParser.parse(parser, now - 2 * HOUR, now + GUIDE_HOURS * HOUR, wanted, Locale.getDefault().language) { p ->
                    pending += entity(p, p.channel)
                    if (pending.size >= BATCH) {
                        val chunk = pending.toList(); pending.clear(); runBlocking { dao.insertProgrammes(chunk) }
                        kept += chunk.size; t.rows(kept, 30_000, "programmes")
                    }
                }
                if (pending.isNotEmpty()) dao.insertProgrammes(pending)
            }
        } catch (e: Exception) {
            android.util.Log.w("Catalog", "guide load failed: ${e.javaClass.simpleName}")
        }
    }

    /** Network names whose guide links are logged (tag GuideMatch): names and guide ids only, never a URL. */
    private val TRACED = Regex("""(?i)\b(global|ctv)\b""")

    private fun trace(c: ChannelEntity, linked: String, why: String) =
        android.util.Log.i("GuideMatch", "${c.name.take(60)} -> provider epgId ${c.epgId ?: "none"} -> linked $linked ($why)")

    private fun pct(f: Float) = "${(f * 100).roundToInt()}%"

    private fun entity(p: XmltvProgramme, epgId: String) = ProgrammeEntity(
        epgId = epgId, start = p.start, stop = p.stop, title = p.title, description = p.description,
        subTitle = p.subTitle, season = p.season, episode = p.episode,
        category = p.categories.joinToString(", ").ifEmpty { null }, icon = p.icon, rating = p.rating,
        year = p.year, isNew = p.isNew, cast = p.cast.joinToString(", ").ifEmpty { null },
    )

    /**
     * The profile's extra guides, in priority order. Each provider channel is linked to at most one
     * extra-guide channel (see [GuideMatch]); a channel whose provider schedule covers most of the
     * next day keeps it and only gains missing details, and a thin one shows the extra schedule
     * instead. Never both on one channel: their slot times differ. Failures never fail the refresh.
     */
    private suspend fun loadExtraGuides(profile: Profile, urls: List<String>, t: Tracker) {
        dao.clearExtraProgrammes()
        if (urls.isEmpty()) { PlaybackStatus.guide = null; return }
        val channels = ArrayList<ChannelEntity>()
        var offset = 0
        while (true) {
            val page = dao.allChannels(2000, offset)
            if (page.isEmpty()) break
            channels += page; offset += page.size
        }
        if (channels.isEmpty()) return
        val cats = dao.categoryList(Kind.LIVE).associate { (it.sourceId to it.categoryId) to it.name }
        val pins = profile.guidePins.orEmpty().entries.associate { GuideMatch.key(it.key) to it.value.trim() }
        val provider = channels.map { c -> GuideMatch.Channel(c.name, c.epgId, cats[c.sourceId to c.categoryId]) }
        val links = arrayOfNulls<GuideMatch.Link>(channels.size)
        val errors = ArrayList<String>()
        val now = System.currentTimeMillis()

        for ((n, url) in urls.withIndex()) {
            t.step("Loading extra guide ${n + 1} of ${urls.size}", 1f / urls.size)
            val guideChannels = ArrayList<XmltvChannel>()
            val wanted = HashSet<String>()
            try {
                openGuide(url).use { res ->
                    val input = res.body!!.byteStream().let { if (isGzip(url, res)) GZIPInputStream(it, 64 * 1024) else it }
                    val parser = Xml.newPullParser().apply { setInput(input, null) }
                    val pending = ArrayList<ProgrammeEntity>(BATCH)
                    var kept = 0
                    XmltvParser.parse(
                        parser, now - 2 * HOUR, now + GUIDE_HOURS * HOUR, wanted, Locale.getDefault().language,
                        onChannel = guideChannels::add,
                        beforeProgrammes = {
                            // Channels come first in the file: link the ones earlier guides didn't.
                            val index = GuideMatch.Index(guideChannels)
                            for (i in channels.indices) {
                                if (links[i] != null) continue
                                links[i] = GuideMatch.link(provider[i], index, pins[GuideMatch.key(channels[i].name)])?.also { wanted += it.guideId }
                            }
                        },
                    ) { p ->
                        pending += entity(p, GuideMatch.EXTRA_PREFIX + p.channel)
                        if (pending.size >= BATCH) {
                            val chunk = pending.toList(); pending.clear(); runBlocking { dao.insertProgrammes(chunk) }
                            kept += chunk.size; t.rows(kept, 20_000, "programmes")
                        }
                    }
                    if (pending.isNotEmpty()) dao.insertProgrammes(pending)
                }
            } catch (e: Exception) {
                errors += "guide ${n + 1}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // Per channel: keep the provider's schedule (filling gaps) or switch to the extra one.
        val day = now + 24 * HOUR
        val own = channels.map { c -> c.epgId?.takeUnless { it.startsWith(GuideMatch.EXTRA_PREFIX) } }
        val linked = links.filterNotNull()
        val next = (own.filterNotNull() + linked.map { GuideMatch.EXTRA_PREFIX + it.guideId }).distinct()
            .chunked(500).flatMap { dao.programmes(it, now, day) }.groupBy { it.epgId }
        val used = HashSet<String>()
        val filled = HashSet<String>()
        val hidden = profile.hiddenCategories.orEmpty().map { it.lowercase() }.toSet()
        // Thin channels nothing links, the admin's ordered categories first: those are worth a pin.
        val order = profile.categoryOrder?.get(Kind.LIVE).orEmpty().withIndex().associate { (n, name) -> name.lowercase() to n }
        val unmatched = ArrayList<Pair<Int, String>>()
        var thin = 0
        var switched = 0
        for ((i, c) in channels.withIndex()) {
            val ownId = own[i]
            // A regional station the provider points at another station's schedule never shows that one:
            // its own extra schedule, or none ("Global News National" showed "Now: BC 1 News").
            val another = GuideMatch.ownIdIsAnotherStation(provider[i])
            val cover = if (another) 0f else ownId?.let { GuideMatch.coverage(next[it].orEmpty(), c.name, now, day) } ?: 0f
            val link = links[i]
            if (cover < GuideMatch.GOOD_COVERAGE) thin++
            val traced = TRACED.containsMatchIn(c.name)
            if (link == null) {
                if (another) dao.setChannelGuide(c.sourceId, c.streamId, null)
                if (traced) trace(c, "none", if (another) "another station's id, guide cleared" else "own cover ${pct(cover)}")
                val cat = cats[c.sourceId to c.categoryId]?.lowercase()
                if (cover < GuideMatch.GOOD_COVERAGE && cat !in hidden) unmatched += (order[cat] ?: Int.MAX_VALUE) to c.name.take(60)
                continue
            }
            val extraId = GuideMatch.EXTRA_PREFIX + link.guideId
            val extraCover = GuideMatch.coverage(next[extraId].orEmpty(), c.name, now, day)
            if (another && extraCover == 0f) {
                dao.setChannelGuide(c.sourceId, c.streamId, null)
                if (traced) trace(c, link.guideId, "${link.how}, another station's id, extra guide empty, guide cleared")
                continue
            }
            // A regional station the provider points at another city's schedule takes its own.
            val elsewhere = link.how == GuideMatch.How.NAME && extraCover >= GuideMatch.GOOD_COVERAGE && GuideMatch.ownIdIsElsewhere(provider[i])
            if (cover >= GuideMatch.GOOD_COVERAGE && !elsewhere) {
                if (ownId != null && filled.add(ownId)) {
                    val changed = GuideMatch.fill(
                        dao.programmeRows(ownId, now - 2 * HOUR, now + GUIDE_HOURS * HOUR),
                        dao.programmeRows(extraId, now - 2 * HOUR, now + GUIDE_HOURS * HOUR),
                    )
                    if (changed.isNotEmpty()) dao.updateProgrammes(changed)
                }
            } else if (elsewhere || extraCover > cover) {
                dao.setChannelGuide(c.sourceId, c.streamId, extraId)
                used += extraId
                switched++
            }
            if (traced) {
                val kept = cover >= GuideMatch.GOOD_COVERAGE && !elsewhere
                trace(c, link.guideId, "${link.how}, ${if (kept) "provider's kept" else if (elsewhere || extraCover > cover) "switched" else "provider's kept (thin)"}, own ${pct(cover)}, extra ${pct(extraCover)}${if (another) ", another station's id" else ""}")
            }
        }
        linked.map { GuideMatch.EXTRA_PREFIX + it.guideId }.distinct().filter { it !in used }.chunked(500).forEach { dao.deleteProgrammes(it) }
        PlaybackStatus.guide = mapOf(
            "channels" to channels.size,
            "thin" to thin,
            "linked" to linked.groupingBy { it.how.name.lowercase() }.eachCount(),
            "switched" to switched,
            "unmatchedCount" to unmatched.size,
            "unmatched" to unmatched.sortedBy { it.first }.take(MAX_REPORTED).map { it.second },
            "errors" to errors.takeIf { it.isNotEmpty() },
        )
    }

    /** Seasons, episodes (with stills and synopses) and the show's own plot, from get_series_info. */
    suspend fun seriesInfo(sourceId: String, seriesId: String, s: Source): XtreamParser.SeriesInfo = withContext(Dispatchers.IO) {
        val server = ServerSwitch.server(s, dao.source(sourceId)?.activeServer)!!
        get(XtreamUrls.api(server, s.username!!, s.password.orEmpty(), "get_series_info", mapOf("series_id" to seriesId))) { XtreamParser.seriesInfo(it) }
    }

    /** TMDB/IMDb ids and year from get_vod_info or get_series_info (Xtream only). */
    suspend fun infoIds(sourceId: String, id: String, s: Source, series: Boolean): XtInfoIds = withContext(Dispatchers.IO) {
        val server = ServerSwitch.server(s, dao.source(sourceId)?.activeServer)!!
        val (action, param) = if (series) "get_series_info" to "series_id" else "get_vod_info" to "vod_id"
        get(XtreamUrls.api(server, s.username!!, s.password.orEmpty(), action, mapOf(param to id))) { XtreamParser.infoIds(it) }
    }

    /** get_vod_info's trailer and TMDB id for a movie (Xtream only). */
    suspend fun vodInfo(sourceId: String, streamId: String, s: Source): XtreamParser.VodInfo = withContext(Dispatchers.IO) {
        val server = ServerSwitch.server(s, dao.source(sourceId)?.activeServer)!!
        get(XtreamUrls.api(server, s.username!!, s.password.orEmpty(), "get_vod_info", mapOf("vod_id" to streamId))) { XtreamParser.vodInfo(it) }
    }

    // --- streaming helpers ---

    /** Streams an Xtream list and writes it [BATCH] rows at a time (runs on an IO thread); [map] returns null for a row to leave out. */
    private suspend fun <T : Any> streamBatches(url: String, write: suspend (List<T>) -> Unit, progress: (Int) -> Unit, map: (XtStream) -> T?) {
        val buf = ArrayList<T>(BATCH)
        var n = 0
        open(url).use { res ->
            XtreamParser.streams(reader(res)) { st ->
                map(st)?.let { buf += it }
                if (buf.size >= BATCH) { val chunk = buf.toList(); buf.clear(); runBlocking { write(chunk) }; n += chunk.size; progress(n) }
            }
        }
        if (buf.isNotEmpty()) { write(buf); n += buf.size; progress(n) }
    }

    /**
     * An extra guide, tried once more after a pause when it fails: its host rewrites the files
     * often, and a TV that gives up on one answer goes without the guide until its next refresh.
     */
    private suspend fun openGuide(url: String): Response =
        try {
            open(url)
        } catch (e: IOException) {
            delay(GUIDE_RETRY_MS)
            open(url)
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
        const val MAX_EXTRA_GUIDES = 4
        private const val COMPLETE = "complete"
        private const val FORMAT_KEY = "format"

        /**
         * How lists are stored and matched: raise it with any change to search text or guide
         * matching, so TVs updated over an existing install load their lists again once.
         * 2: search on clean titles and guide links for regional stations (Oct 26).
         */
        const val FORMAT = 2
        const val GUIDE_RETRY_MS = 20_000L
        /** Thin channels with no extra guide, listed for the admin (the portal keeps a few KB of status). */
        const val MAX_REPORTED = 30
    }
}
