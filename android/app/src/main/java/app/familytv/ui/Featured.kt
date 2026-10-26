package app.familytv.ui

import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.core.Http
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Names
import app.familytv.data.ProgrammeEntity
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.releaseYear
import app.familytv.trailer.Tmdb
import app.familytv.trailer.TmdbTitle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One slide of the Home carousel: a film or show the line has, or an upcoming PPV / special event. */
internal class Slide(
    val key: String,
    val title: String,
    /** TMDB's title logo, drawn instead of the title when there is one. */
    val logo: String?,
    /** Full-bleed TMDB backdrop; null for an event, which shows [art] over a dark sports backdrop. */
    val backdrop: String?,
    val art: String?,
    /** "2024 · Action · ★ 7.8", or "Live event · Sat 9:00 PM". */
    val detail: String,
    val target: Target,
) {
    sealed interface Target {
        class Movie(val g: MovieGroup) : Target
        class Show(val g: SeriesGroup) : Target
        class Event(val channel: ChannelEntity, val start: Long) : Target
    }
}

/**
 * The Home carousel's slides: TMDB's top films and shows this week (popular ones fill in when
 * fewer than [MIN_TITLES] are on the line) that this profile's visible categories have, so OK
 * always opens something playable, plus PPV and special events in the guide for the next 7 days.
 * TMDB's lists and logos are kept for [TTL_MS]; matching runs on the TV, which holds the catalogue.
 */
internal object Featured {
    const val MOVIES = 5
    const val SHOWS = 5
    const val EVENTS = 3
    /** The most slides, whatever the mix. */
    const val MAX_SLIDES = 12
    const val MIN_TITLES = 6
    private const val TTL_MS = 6 * 3_600_000L
    private const val EVENT_DAYS = 7
    private const val POPULAR_PAGES = 2
    /** The screensaver's most films, and most shows, from TMDB's lists. */
    private const val ART_EACH = 20

    private val EVENT_CATEGORY = Regex("""\bPPV\b|\bEVENTS?\b""", RegexOption.IGNORE_CASE)
    /** Guide fillers between events, never an event of their own. */
    private val FILLER = Regex("""^(no (event|programme|program|information)|off air|tba|tbd|to be announced|coming soon|stay tuned|event (starts|start) soon)""", RegexOption.IGNORE_CASE)

    /** The slides last made, and for whom, so Home shows the carousel at once when it comes back while they refresh. */
    @Volatile private var last: Pair<Any, List<Slide>>? = null

    /** The slides last made for [owner] (the profile as this viewer sees it), or none. */
    fun cached(owner: Any): List<Slide> = last?.takeIf { it.first == owner }?.second.orEmpty()

    /**
     * Makes the slides for [owner] off the main thread. When they come out the same as [cached]'s,
     * those very objects come back, so a refresh doesn't restart the carousel.
     */
    suspend fun load(app: FamilyTvApp, owner: Any, movies: List<CategoryEntity>, shows: List<CategoryEntity>, live: List<CategoryEntity>, now: Long = System.currentTimeMillis()): List<Slide> =
        withContext(Dispatchers.Default) {
            val made = make(app, movies, shows, live, now)
            val before = cached(owner)
            val out = if (before.map { it.key to it.detail } == made.map { it.key to it.detail }) before else made
            if (out.isNotEmpty()) last = owner to out
            out
        }

    private suspend fun make(app: FamilyTvApp, movies: List<CategoryEntity>, shows: List<CategoryEntity>, live: List<CategoryEntity>, now: Long): List<Slide> {
        val key = app.portal.cachedConfig?.tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() }
        val lists = key?.let { runCatching { lists(app, Tmdb(it, Http.client), now) }.getOrNull() }
        val films = lists?.let { pickMovies(app, it, categoryKeys(movies)) }.orEmpty()
        val series = lists?.let { pickShows(app, it, categoryKeys(shows)) }.orEmpty()
        val withLogos = key?.let { k -> logos(app, Tmdb(k, Http.client), films.map { it.second } + series.map { it.second }, now) }.orEmpty()
        val titles = films.map { (g, t) -> titleSlide(t, Slide.Target.Movie(g), withLogos[logoKey(t)]) } +
            series.map { (g, t) -> titleSlide(t, Slide.Target.Show(g), withLogos[logoKey(t)]) }
        return mix(titles.filter { it.target is Slide.Target.Movie }, titles.filter { it.target is Slide.Target.Show }, events(app, live, now))
    }

    /** Films and shows alternate; an event comes after every two of them. */
    fun mix(movies: List<Slide>, shows: List<Slide>, events: List<Slide>): List<Slide> {
        val titles = (0 until maxOf(movies.size, shows.size)).flatMap { listOfNotNull(movies.getOrNull(it), shows.getOrNull(it)) }
        val out = ArrayList<Slide>()
        val ev = events.take(EVENTS).iterator()
        titles.forEachIndexed { i, s -> out += s; if (i % 2 == 1 && ev.hasNext()) out += ev.next() }
        ev.forEachRemaining { out += it }
        return out.take(MAX_SLIDES)
    }

    /** "2024 · Action · ★ 7.8": whatever TMDB knows of the three. */
    fun detail(t: TmdbTitle): String = listOfNotNull(
        t.year?.toString(),
        t.genres.firstNotNullOfOrNull { (if (t.tv) TV_GENRES else MOVIE_GENRES)[it] },
        t.rating?.let { "★ %.1f".format(Locale.US, it) },
    ).joinToString("  ·  ")

    fun eventDetail(start: Long, now: Long): String = if (start <= now) "Live event  ·  On now" else "Live event  ·  ${startsAt(start)}"

    /** "Sat 9:00 PM" in the TV's own time zone and language. */
    fun startsAt(start: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(start))

    fun isEventCategory(name: String) = EVENT_CATEGORY.containsMatchIn(Names.clean(name))

    fun isFiller(title: String) = FILLER.containsMatchIn(title.trim())

    private fun titleSlide(t: TmdbTitle, target: Slide.Target, logo: String?) =
        Slide("${if (t.tv) "tv" else "movie"}/${t.id}", t.title, logo, t.backdrop, null, detail(t), target)

    // ---- TMDB lists, cached ----

    /** Kept as JSON in the "featured" preferences (see proguard-rules.pro). */
    private class Lists(val at: Long, val movies: List<TmdbTitle>, val shows: List<TmdbTitle>, val popularMovies: List<TmdbTitle>, val popularShows: List<TmdbTitle>)

    private val gson = Gson()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("featured", Context.MODE_PRIVATE)

    private suspend fun lists(ctx: Context, tmdb: Tmdb, now: Long): Lists = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        runCatching { gson.fromJson(p.getString("lists", null), Lists::class.java) }.getOrNull()?.takeIf { now - it.at in 0 until TTL_MS && it.movies.isNotEmpty() }?.let { return@withContext it }
        val l = Lists(
            now, tmdb.trending(false), tmdb.trending(true),
            (1..POPULAR_PAGES).flatMap { tmdb.popular(false, it) }, (1..POPULAR_PAGES).flatMap { tmdb.popular(true, it) },
        )
        p.edit().putString("lists", gson.toJson(l)).apply()
        l
    }

    private fun logoKey(t: TmdbTitle) = "${if (t.tv) "tv" else "movie"}/${t.id}"

    /** Title logos for [titles]: one lookup each, kept as long as the lists (a miss is kept too, as ""). */
    private suspend fun logos(ctx: Context, tmdb: Tmdb, titles: List<TmdbTitle>, now: Long): Map<String, String?> = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val at = p.getLong("logosAt", 0)
        val fresh = now - at in 0 until TTL_MS
        val type = object : TypeToken<Map<String, String>>() {}.type
        val known = (if (fresh) runCatching { gson.fromJson<Map<String, String>>(p.getString("logos", null), type) }.getOrNull() else null).orEmpty().toMutableMap()
        val missing = titles.filter { logoKey(it) !in known }
        missing.forEach { t -> known[logoKey(t)] = runCatching { tmdb.logo(t.tv, t.id) }.getOrNull().orEmpty() }
        if (missing.isNotEmpty()) p.edit().putString("logos", gson.toJson(known)).putLong("logosAt", if (fresh) at else now).apply()
        known.mapValues { it.value.ifEmpty { null } }
    }

    // ---- matching against the line ----

    /**
     * The screensaver's "Movie and show art": backdrops of TMDB's top and popular films and shows
     * that this profile's visible categories have (so a kids viewer gets kids' titles), with each
     * title as its caption. Empty without a TMDB key or before the lists have ever loaded.
     */
    suspend fun art(app: FamilyTvApp, movies: List<CategoryEntity>, shows: List<CategoryEntity>, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val key = app.portal.cachedConfig?.tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val l = runCatching { lists(app, Tmdb(key, Http.client), now) }.getOrNull() ?: return emptyList()
        val films = pickMovies(app, l, categoryKeys(movies), ART_EACH, fillBelow = ART_EACH)
        val series = pickShows(app, l, categoryKeys(shows), ART_EACH, fillBelow = ART_EACH)
        return (films.map { it.second } + series.map { it.second }).mapNotNull { t -> t.backdrop?.let { it to t.title } }.distinctBy { it.first }
    }

    private suspend fun pickMovies(app: FamilyTvApp, l: Lists, visible: Set<Pair<String, String>>, max: Int = MOVIES, fillBelow: Int = MIN_TITLES / 2): List<Pair<MovieGroup, TmdbTitle>> {
        val dao = app.db.catalog()
        val out = LinkedHashMap<String, Pair<MovieGroup, TmdbTitle>>()
        suspend fun take(list: List<TmdbTitle>, max: Int) {
            for (t in list) {
                if (out.size >= max) return
                if (out.values.any { it.second.id == t.id }) continue
                val byId = dao.moviesByTmdb(listOf(t.id))
                val rows = byId.ifEmpty { dao.movieCopies(listOf(SeriesTitles.key(t.title)), emptyList()).filter { m -> sameYear(m.releaseYear, t.year) && m.tmdb.let { it == null || it == t.id } } }
                    .filter { (it.sourceId to it.categoryId) in visible }
                val g = groupMovies(rows).maxByOrNull { it.copies.size } ?: continue
                if (out.values.any { it.first.key == g.key }) continue
                out[t.id] = g to t
            }
        }
        take(l.movies, max)
        if (out.size < fillBelow) take(l.popularMovies, max)
        return out.values.toList()
    }

    private suspend fun pickShows(app: FamilyTvApp, l: Lists, visible: Set<Pair<String, String>>, max: Int = SHOWS, fillBelow: Int = MIN_TITLES / 2): List<Pair<SeriesGroup, TmdbTitle>> {
        val dao = app.db.catalog()
        val out = LinkedHashMap<String, Pair<SeriesGroup, TmdbTitle>>()
        suspend fun take(list: List<TmdbTitle>, max: Int) {
            for (t in list) {
                if (out.size >= max) return
                if (out.values.any { it.second.id == t.id }) continue
                val byId = dao.seriesByTmdb(listOf(t.id))
                val rows = byId.ifEmpty { dao.seriesCopies(listOf(SeriesTitles.key(t.title)), emptyList()).filter { s -> sameYear(s.year, t.year) && s.tmdb.let { it == null || it == t.id } } }
                    .filter { (it.sourceId to it.categoryId) in visible }
                val g = groupSeries(rows).maxByOrNull { it.copies.size } ?: continue
                if (out.values.any { it.first.key == g.key }) continue
                out[t.id] = g to t
            }
        }
        take(l.shows, max)
        if (out.size < fillBelow) take(l.popularShows, max)
        return out.values.toList()
    }

    /** A title match needs the same year, give or take one, when both sides know it. */
    private fun sameYear(a: Int?, b: Int?) = a == null || b == null || abs(a - b) <= 1

    // ---- events from the guide ----

    private suspend fun events(app: FamilyTvApp, live: List<CategoryEntity>, now: Long): List<Slide> = withContext(Dispatchers.IO) {
        val dao = app.db.catalog()
        val cats = live.filter { isEventCategory(it.name) }
        val until = now + EVENT_DAYS * 86_400_000L
        val found = ArrayList<Pair<ChannelEntity, ProgrammeEntity>>()
        for ((source, inSource) in cats.groupBy { it.sourceId }) {
            val channels = dao.channelsIn(source, inSource.map { it.categoryId }).filter { !it.epgId.isNullOrEmpty() }
            for (c in channels) {
                dao.programmeRows(c.epgId!!, now, until).firstOrNull { !isFiller(it.title) }?.let { found += c to it }
            }
        }
        found.sortedBy { it.second.start }.distinctBy { (_, p) -> p.title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() } }.take(EVENTS).map { (c, p) ->
            Slide("event/${c.sourceId}/${c.streamId}/${p.start}", p.title, null, null, p.icon ?: c.logo, eventDetail(p.start, now), Slide.Target.Event(c, p.start))
        }
    }

    private val MOVIE_GENRES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
        878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
    )
    private val TV_GENRES = mapOf(
        10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 10762 to "Kids", 9648 to "Mystery", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy",
        10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics", 37 to "Western",
    )
}

/** The copy of an event's channel the player opens: its place in its category's list. */
internal suspend fun eventIndex(app: FamilyTvApp, c: ChannelEntity): Int =
    app.db.catalog().channelsIn(c.sourceId, listOf(c.categoryId)).sortedWith(compareBy({ it.num }, { it.name })).indexOfFirst { it.streamId == c.streamId }.coerceAtLeast(0)
