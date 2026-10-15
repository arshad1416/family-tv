package app.familytv.trailer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.familytv.FamilyTvApp
import app.familytv.core.Http
import app.familytv.data.MovieEntity
import app.familytv.data.ProgrammeEntity
import app.familytv.data.SeriesEntity
import app.familytv.iptv.GuideTitles
import app.familytv.iptv.SeriesTitles
import app.familytv.portal.Profile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Show details the guide left out, and where they came from (the guide header credits it). */
data class ShowExtras(val overview: String?, val image: String?, val source: String)

/** Season details from TMDB, else TheTVDB, to fill what the provider's get_series_info left out. */
data class SeasonExtras(val overview: String?, val trailer: String?, val episodes: Map<Int, EpisodeMeta>)

/**
 * Trailers and episode details beyond the provider's own data, in order: the provider (get_vod_info,
 * get_series_info), TMDB, then TheTVDB, each only when the portal sent its key. Results (misses too)
 * are cached in memory for [TTL_MS], and at most two lookups run at once, so browsing never hammers
 * the provider or the services.
 */
class MediaExtras(private val app: FamilyTvApp) {
    private val cache = ConcurrentHashMap<String, Pair<Long, Any?>>()
    private val gate = Semaphore(2)
    private val tvdbPrefs by lazy { app.getSharedPreferences("metadata", Context.MODE_PRIVATE) }

    private fun tmdb(): Tmdb? = app.portal.cachedConfig?.tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { Tmdb(it, Http.client) }
    /** One TVDB client per key, so its login token is reused. */
    @Volatile private var tvdbClient: Pair<String, Tvdb>? = null
    private fun tvdb(): Tvdb? {
        val key = app.portal.cachedConfig?.tvdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        tvdbClient?.let { (k, c) -> if (k == key) return c }
        return Tvdb(key, Http.client, tvdbPrefs).also { tvdbClient = key to it }
    }

    /** Cached result of [load], or null when it failed; failures are cached too. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, load: suspend () -> T?): T? {
        cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < TTL_MS) return v as T? }
        // A lookup nested in another (a season needing the show's TMDB id) reuses the outer permit;
        // taking a second one could deadlock two lookups that each hold one.
        val v = if (currentCoroutineContext()[Gated] != null) runCatching { load() }.getOrNull()
        else gate.withPermit { withContext(Gated) { runCatching { load() }.getOrNull() } }
        cache[key] = System.currentTimeMillis() to v
        return v
    }

    /**
     * A movie's YouTube trailer id: the provider's (get_vod_info), else TMDB's best, else TheTVDB's.
     * Called only when the Trailer button is pressed, never while browsing.
     */
    suspend fun movieTrailer(profile: Profile, m: MovieEntity): String? = cached("movie/${m.sourceId}/${m.streamId}") {
        val src = profile.sources.firstOrNull { it.id == m.sourceId && it.type == "xtream" }
        val info = src?.let { runCatching { app.catalog.vodInfo(m.sourceId, m.streamId, it) }.getOrNull() }
        val langs = languages(null)
        val title = SeriesTitles.parse(m.name).title
        val tmdbId = m.tmdb ?: info?.tmdb
        info?.trailer
            ?: runCatching { tmdb()?.let { t -> (tmdbId ?: t.searchMovie(title, m.year))?.let { Trailers.pick(t.movieVideos(it, langs), langs) } } }.getOrNull()
            ?: tvdb()?.let { t -> t.movieId(tmdbId, null, title, m.year)?.let { t.movieTrailer(it, langs) } }
    }

    /**
     * A movie's overview, genres and running time from TMDB, for the Movies spotlight. Only TMDB is
     * asked (never the provider, which throttles bursts), and only once focus rests on the poster.
     */
    suspend fun movieMeta(m: MovieEntity): MovieMeta? = cached("movie-meta/${m.sourceId}/${m.streamId}") {
        val t = tmdb() ?: return@cached null
        val id = m.tmdb ?: t.searchMovie(SeriesTitles.parse(m.name).title, m.year) ?: return@cached null
        t.movie(id, languages(null).first())
    }

    /**
     * Details for a guide programme that came without a description or picture: TMDB (movie, or
     * show and episode) when the portal set a key, else TVmaze for shows. Only asked for the
     * programme focus rests on; news, sport and the like are never looked up.
     */
    suspend fun showExtras(p: ProgrammeEntity): ShowExtras? {
        if (!GuideTitles.worthLookingUp(p.title, p.category, p.description, p.icon)) return null
        val title = GuideTitles.clean(p.title)
        val movie = p.category?.let { MOVIE.containsMatchIn(it) } == true
        val s = p.season
        val e = p.episode
        return cached("guide/${GuideTitles.key(title)}/${if (movie) "movie/${p.year}" else "$s/$e"}") {
            val lang = languages(null).first()
            runCatching {
                tmdb()?.let { t ->
                    val found = if (movie) {
                        t.searchMovie(title, p.year)?.let { id -> t.movie(id, lang) }?.let { ShowExtras(it.overview, it.poster, "TMDB") }
                    } else {
                        t.searchTv(title, null)?.let { id ->
                            val ep = if (s != null && e != null) t.season(id, s, listOf(lang))?.episodes?.get(e) else null
                            val show = t.tv(id, lang)
                            ShowExtras(ep?.overview ?: show?.overview, ep?.still ?: show?.poster, "TMDB")
                        }
                    }
                    if (found != null && (found.overview != null || found.image != null)) return@cached found
                }
            }
            if (movie) return@cached null
            val maze = TvMaze(Http.client)
            // TVmaze's single search always answers something; only trust it when the name matches.
            val (id, show) = maze.show(title)?.takeIf { (_, m) -> m.name?.let(GuideTitles::key) == GuideTitles.key(title) } ?: return@cached null
            val ep = if (s != null && e != null) runCatching { maze.episode(id, s, e) }.getOrNull() else null
            ShowExtras(ep?.summary ?: show.summary, ep?.image ?: show.image, "TVmaze").takeIf { it.overview != null || it.image != null }
        }
    }

    /** The show's trailer from TMDB, else TheTVDB (the caller tries the provider's first). */
    suspend fun showTrailer(v: SeriesEntity): String? = cached("show/${v.sourceId}/${v.seriesId}") {
        val langs = languages(v.lang)
        tmdbTvId(v)?.let { id -> tmdb()?.let { Trailers.pick(it.tvVideos(id, langs), langs) } }
            ?: tvdbId(v)?.let { id -> tvdb()?.trailer(id, langs) }
    }

    /** One season's overview, trailer and episode stills/overviews from TMDB, with TheTVDB filling gaps. */
    suspend fun season(v: SeriesEntity, season: Int): SeasonExtras? = cached("season/${v.sourceId}/${v.seriesId}/$season") {
        val langs = languages(v.lang)
        val fromTmdb = tmdbTvId(v)?.let { id -> tmdb()?.season(id, season, langs) }
        val episodes = fromTmdb?.episodes.orEmpty().toMutableMap()
        val gaps = episodes.isEmpty() || episodes.values.any { it.still == null || it.overview == null }
        if (gaps) tvdbId(v)?.let { id -> tvdbEpisodes(id) }?.forEach { (k, meta) ->
            if (k.first != season) return@forEach
            val have = episodes[k.second]
            episodes[k.second] = EpisodeMeta(have?.still ?: meta.still, have?.overview ?: meta.overview)
        }
        SeasonExtras(fromTmdb?.overview, fromTmdb?.videos?.let { Trailers.pick(it, langs) }, episodes)
    }

    private suspend fun tvdbEpisodes(id: String) = cached("tvdb-episodes/$id") { tvdb()?.episodes(id) }

    private suspend fun tmdbTvId(v: SeriesEntity): String? = v.tmdb ?: cached("tmdb-tv/${v.sourceId}/${v.seriesId}") {
        val p = SeriesTitles.parse(v.name)
        tmdb()?.searchTv(p.title, v.year)
    }

    private suspend fun tvdbId(v: SeriesEntity): String? = cached("tvdb-tv/${v.sourceId}/${v.seriesId}") {
        tvdb()?.seriesId(v.tmdb, null, SeriesTitles.parse(v.name).title, v.year)
    }

    /** Trailer languages to prefer: the copy's own, then the device's, then English. */
    private fun languages(lang: String?): List<String> =
        listOfNotNull(lang?.takeIf { it != SeriesTitles.ORIGINAL }, Locale.getDefault().language, "en").map { it.substringBefore('-') }.distinct()

    /** Marks a coroutine that already holds a [gate] permit. */
    private object Gated : AbstractCoroutineContextElement(Gated), CoroutineContext.Key<Gated>

    companion object {
        private val MOVIE = Regex("(?i)\\b(movie|film)s?\\b")
        const val TTL_MS = 12 * 3_600_000L
    }
}

/**
 * Opens a YouTube trailer in the YouTube app (vnd.youtube:, which Android TV and Google TV
 * handle), else in a browser; says so when nothing can (some Fire TV devices).
 */
fun openTrailer(ctx: Context, youtubeId: String) {
    for (uri in listOf(Trailers.appUri(youtubeId), Trailers.webUrl(youtubeId))) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(ctx.applicationContext, "No app on this device can play YouTube trailers.", Toast.LENGTH_LONG).show()
}
