package app.familytv.trakt

import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import app.familytv.data.searchMovies
import app.familytv.data.searchSeries
import app.familytv.trakt.model.TIds
import app.familytv.trakt.model.TListItem
import app.familytv.trakt.model.TMedia
import app.familytv.trakt.model.TPlayback
import app.familytv.trakt.model.TSearchHit
import app.familytv.trakt.model.TWatchedMovie
import app.familytv.trakt.model.TWatchedShow
import app.familytv.user.FavouriteEntity
import app.familytv.user.MediaIdsEntity
import app.familytv.user.Pending
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/**
 * Keeps this device and the Trakt account in step:
 * - movie and series favourites <-> the Trakt watchlist (two-way, see [WatchlistMerge]);
 * - watched marks: pulled from sync/watched, pushed by scrobble stop (or sync/history when that failed
 *   or a mark was set by hand; sync/history/remove when one was taken off);
 * - resume points: pulled from sync/playback, pushed by scrobble start/pause/stop.
 * Live channels have no Trakt equivalent, so channel favourites never leave the device.
 */
class TraktSync(private val app: FamilyTvApp, private val trakt: Trakt) {
    private val lock = Mutex()
    private val scrobbleLock = Mutex()
    private var delayJob: Job? = null
    private val user get() = app.userDb.user()
    private val catalog get() = app.db.catalog()

    private data class Ref(val kind: String, val sourceId: String, val itemId: String)

    /** Caps Trakt title searches per sync so a big favourites list can't hammer the API. */
    private class Budget(var left: Int)

    /** Syncs after [delayMs]; a burst of favourite taps becomes one sync. */
    fun soon(delayMs: Long = 2_000) {
        if (!trakt.signedIn) return
        delayJob?.cancel()
        delayJob = app.scope.launch {
            delay(delayMs)
            // A separate job, so a later tap can't cancel a sync that already started.
            app.scope.launch { runCatching { now() } }
        }
    }

    suspend fun now() {
        if (!trakt.signedIn) return
        lock.withLock {
            trakt.syncStarted()
            val error = try {
                val budget = Budget(MAX_SEARCHES)
                watchlist(budget)
                history(budget)
                watched()
                playback()
                null
            } catch (e: CancellationException) {
                trakt.syncFinished(null)
                throw e
            } catch (e: TraktException) {
                if (e.code == 401) null else "Last sync failed (Trakt error ${e.code}). It will try again."
            } catch (e: Exception) {
                "Couldn't reach Trakt. It will try again."
            }
            trakt.syncFinished(error)
        }
    }

    // ---- favourites <-> watchlist ----

    private suspend fun watchlist(budget: Budget) {
        val rows = user.allFavouriteRows().filter { it.kind != Kind.LIVE && !it.fromHistory }
        val local = rows.map { f ->
            // Only new favourites are worth a title search; synced ones were matched when they went up.
            val search = f.pending == Pending.ADD || (f.pending == null && !f.onTrakt)
            val ids = idsFor(f.kind, f.sourceId, f.itemId, if (search) budget else null) ?: MediaIds()
            val op = when (f.pending) { Pending.ADD -> WatchlistMerge.Op.ADD; Pending.REMOVE -> WatchlistMerge.Op.REMOVE; else -> null }
            WatchlistMerge.LocalFav(f, f.kind, ids, op, f.onTrakt)
        }
        val remote = list<TListItem>("/sync/watchlist/movies").mapNotNull { remote(Kind.MOVIE, it.movie) } +
            list<TListItem>("/sync/watchlist/shows").mapNotNull { remote(Kind.SERIES, it.show) }
        val plan = WatchlistMerge.plan(local, remote)

        if (plan.pushAdd.isNotEmpty()) {
            trakt.post("/sync/watchlist", idsBody(plan.pushAdd.map { it.kind to it.ids }), onAccepted = {
                user.upsertFavourites(plan.pushAdd.map { it.key.copy(pending = null, onTrakt = true) })
            })
        }
        if (plan.pushRemove.isNotEmpty()) {
            trakt.post("/sync/watchlist/remove", idsBody(plan.pushRemove.map { it.kind to it.ids }), onAccepted = {
                plan.pushRemove.forEach { delete(it.key) }
            })
        }
        if (plan.markSynced.isNotEmpty()) user.upsertFavourites(plan.markSynced.map { it.copy(pending = null, onTrakt = true) })
        plan.deleteLocal.forEach { delete(it) }

        // Added on another device: favourite every copy the catalog has.
        for (r in plan.pull) {
            val refs = refs(r.kind, listOf(r.ids)).first().ifEmpty { refsByTitle(r.kind, r.title, r.year) }
            for (ref in refs) {
                val existing = user.favourite(ref.kind, ref.sourceId, ref.itemId)
                // Followed from history, now on the watchlist too: an ordinary synced favourite.
                if (existing?.fromHistory == true) user.upsertFavourite(existing.copy(fromHistory = false, pending = null, onTrakt = true))
                if (existing != null) continue
                val name = catalogName(ref) ?: continue
                user.upsertFavourite(FavouriteEntity(ref.kind, ref.sourceId, ref.itemId, name, System.currentTimeMillis(), pending = null, onTrakt = true))
                rememberIds(ref, r.ids)
            }
        }
    }

    private fun remote(kind: String, m: TMedia?): WatchlistMerge.Remote? {
        val ids = m?.ids?.toIds()?.takeUnless { it.isEmpty } ?: return null
        return WatchlistMerge.Remote(kind, ids, m.title.orEmpty(), m.year)
    }

    private suspend fun delete(f: FavouriteEntity) = user.deleteFavourite(f.kind, f.sourceId, f.itemId)

    private fun idsBody(items: List<Pair<String, MediaIds>>): Map<String, Any> = mapOf(
        "movies" to items.filter { it.first == Kind.MOVIE }.map { mapOf("ids" to it.second.toTrakt()) },
        "shows" to items.filter { it.first == Kind.SERIES }.map { mapOf("ids" to it.second.toTrakt()) },
    )

    // ---- watched ----

    /**
     * Watched here while Trakt couldn't be reached, or marked watched or unwatched by hand: sent
     * through sync/history (or sync/history/remove).
     */
    private suspend fun history(budget: Budget) {
        val rows = user.pendingHistory()
        if (rows.isEmpty()) return
        val (add, remove) = rows.partition { it.watched }
        historyCall("/sync/history", add, budget, dated = true)
        currentCoroutineContext().ensureActive()
        historyCall("/sync/history/remove", remove, budget, dated = false)
    }

    /** Posts [rows] to [path] and returns the keys it sent (rows with no Trakt match wait for a later sync). */
    private suspend fun historyCall(path: String, rows: List<ProgressEntity>, budget: Budget, dated: Boolean): List<String> {
        val movies = ArrayList<Map<String, Any>>()
        val shows = LinkedHashMap<MediaIds, MutableMap<Int, MutableList<Map<String, Any>>>>()
        val sent = ArrayList<String>()
        for (p in rows) {
            val movie = p.kind == ProgressKey.MOVIE
            val ids = idsFor(if (movie) Kind.MOVIE else Kind.SERIES, p.sourceId, p.itemId, budget) ?: continue
            val at = if (dated) mapOf("watched_at" to iso(p.updatedAt)) else emptyMap()
            if (movie) movies += mapOf("ids" to ids.toTrakt()) + at
            else shows.getOrPut(ids) { sortedMapOf() }.getOrPut(p.season) { ArrayList() } += mapOf("number" to p.episode) + at
            sent += p.key
        }
        if (sent.isEmpty()) return sent
        trakt.post(
            path,
            mapOf(
                "movies" to movies,
                "shows" to shows.map { (ids, seasons) ->
                    mapOf("ids" to ids.toTrakt(), "seasons" to seasons.map { (n, eps) -> mapOf("number" to n, "episodes" to eps) })
                },
            ),
            onAccepted = { sent.chunked(500).forEach { user.clearPendingHistory(it) } },
        )
        return sent
    }

    /** Watched marks from other devices and other Trakt apps, for every title the catalog has. */
    private suspend fun watched() {
        val updates = ArrayList<Pair<ProgressEntity, Long>>()
        val movies = list<TWatchedMovie>("/sync/watched/movies").filter { it.movie?.ids != null }
        matched(Kind.MOVIE, movies.map { it.movie!! }).forEachIndexed { i, refs ->
            val at = parse(movies[i].lastWatchedAt)
            refs.forEach { r -> updates += ProgressEntity(ProgressKey.movie(r.sourceId, r.itemId), ProgressKey.MOVIE, r.sourceId, r.itemId) to at }
        }
        val shows = list<TWatchedShow>("/sync/watched/shows").filter { it.show?.ids != null }
        val showRefs = matched(Kind.SERIES, shows.map { it.show!! })
        followShows(showRefs)
        showRefs.forEachIndexed { i, refs ->
            for (r in refs) for (season in shows[i].seasons.orEmpty()) for (ep in season.episodes.orEmpty()) {
                val key = ProgressKey.episode(r.sourceId, r.itemId, season.number, ep.number)
                updates += ProgressEntity(key, ProgressKey.EPISODE, r.sourceId, r.itemId, season.number, ep.number) to parse(ep.lastWatchedAt)
            }
        }
        for (chunk in updates.chunked(500)) {
            val existing = user.progressFor(chunk.map { it.first.key }).associateBy { it.key }
            val rows = chunk.mapNotNull { (fresh, at) ->
                val e = existing[fresh.key]
                when {
                    e == null -> fresh.copy(watched = true, percent = 100f, updatedAt = at)
                    // Changed here since this sync sent history; the next sync sends it first.
                    e.pendingHistory -> null
                    // Finished on another device after it last stopped here.
                    at > e.updatedAt -> e.copy(watched = true, percent = 100f, positionMs = 0, updatedAt = at)
                    !e.watched -> e.copy(watched = true)
                    else -> null
                }
            }
            if (rows.isNotEmpty()) user.upsertProgressRows(rows)
        }
    }

    /**
     * Shows the family watches on Trakt become favourites here, so they sit in Favourites with
     * their progress. Not pushed to the watchlist; one removed here stays removed.
     */
    private suspend fun followShows(shows: List<List<Ref>>) {
        val now = System.currentTimeMillis()
        for (copies in shows) {
            // One favourite per show (the Favourites page groups language copies anyway).
            if (copies.isEmpty() || copies.any { user.favourite(it.kind, it.sourceId, it.itemId) != null }) continue
            val ref = copies.first()
            val name = catalogName(ref) ?: continue
            user.upsertFavourite(FavouriteEntity(ref.kind, ref.sourceId, ref.itemId, name, now, fromHistory = true))
        }
    }

    // ---- resume points ----

    private suspend fun playback() {
        val items = list<TPlayback>("/sync/playback")
        val movies = items.filter { it.type == "movie" && it.movie?.ids != null }
        val episodes = items.filter { it.type == "episode" && it.show?.ids != null && it.episode?.season != null && it.episode.number != null }
        val updates = ArrayList<Pair<ProgressEntity, TPlayback>>()
        matched(Kind.MOVIE, movies.map { it.movie!! }).forEachIndexed { i, refs ->
            refs.forEach { r -> updates += ProgressEntity(ProgressKey.movie(r.sourceId, r.itemId), ProgressKey.MOVIE, r.sourceId, r.itemId) to movies[i] }
        }
        val showRefs = matched(Kind.SERIES, episodes.map { it.show!! })
        followShows(showRefs)
        showRefs.forEachIndexed { i, refs ->
            val p = episodes[i]
            val s = p.episode!!.season!!
            val n = p.episode.number!!
            refs.forEach { r -> updates += ProgressEntity(ProgressKey.episode(r.sourceId, r.itemId, s, n), ProgressKey.EPISODE, r.sourceId, r.itemId, s, n) to p }
        }
        for (chunk in updates.chunked(500)) {
            val existing = user.progressFor(chunk.map { it.first.key }).associateBy { it.key }
            val rows = chunk.mapNotNull { (fresh, p) ->
                val at = parse(p.pausedAt)
                val e = existing[fresh.key]
                if (!ProgressRules.remoteIsNewer(at, e)) return@mapNotNull null
                val pct = (p.progress ?: 0.0).toFloat().coerceIn(0f, 100f)
                val base = e ?: fresh
                base.copy(percent = pct, positionMs = if (base.durationMs > 0) (base.durationMs * pct / 100f).toLong() else 0, updatedAt = at)
            }
            if (rows.isNotEmpty()) user.upsertProgressRows(rows)
        }
    }

    /**
     * Saves where playback is, and tells Trakt for start/pause/stop. Runs in the app scope so
     * the stop still goes out after the player has closed.
     */
    fun record(item: PlayItem, action: String, positionMs: Long, durationMs: Long, percent: Float) {
        app.scope.launch {
            if (item.sourceId == "tmdb" && item.tmdb?.matches(Regex("[1-9][0-9]{0,9}")) == true) {
                user.putIds(MediaIdsEntity(if (item.kind == ProgressKey.MOVIE) Kind.MOVIE else Kind.SERIES,
                    item.sourceId, item.itemId, item.tmdb, item.imdb, null, System.currentTimeMillis()))
            }
            val old = user.progress(item.key)
            val finished = action == STOP && ProgressRules.isWatched(percent)
            val row = (old ?: ProgressEntity(item.key, item.kind, item.sourceId, item.itemId, item.season, item.episode)).copy(
                positionMs = positionMs,
                durationMs = durationMs,
                percent = percent,
                watched = old?.watched == true || finished,
                updatedAt = System.currentTimeMillis(),
            )
            user.upsertProgress(row)
            if (action != TICK) app.sharedProgress.soon()
            if (action == TICK || !trakt.signedIn) return@launch
            val sent = runCatching { scrobble(item, action, percent) }.isSuccess
            if (finished && !sent) user.upsertProgress(row.copy(pendingHistory = true))
        }
    }

    private suspend fun scrobble(item: PlayItem, action: String, percent: Float) = scrobbleLock.withLock {
        val movie = item.kind == ProgressKey.MOVIE
        val ids = idsFor(if (movie) Kind.MOVIE else Kind.SERIES, item.sourceId, item.itemId, Budget(1))
            ?: throw IOException("no Trakt match for ${item.name}")
        val body = if (movie) {
            mapOf("movie" to mapOf("ids" to ids.toTrakt()), "progress" to percent)
        } else {
            mapOf("show" to mapOf("ids" to ids.toTrakt()), "episode" to mapOf("season" to item.season, "number" to item.episode), "progress" to percent)
        }
        trakt.post("/scrobble/$action", body)
    }

    // ---- matching ----

    /**
     * Trakt ids for a catalog movie or series: the provider's TMDB id, a cached lookup, the
     * provider's info call, then (with a [budget]) a Trakt title search. Network failures are
     * not cached, so the item is tried again next time; "no match" is retried after a week.
     */
    private suspend fun idsFor(kind: String, sourceId: String, itemId: String, budget: Budget?): MediaIds? {
        val row = when (kind) {
            Kind.MOVIE -> catalog.movie(sourceId, itemId)?.let { Triple(it.name, it.tmdb, it.year) }
            else -> catalog.seriesItem(sourceId, itemId)?.let { Triple(it.name, it.tmdb, it.year) }
        }
        row?.second?.let { return MediaIds(tmdb = it) }
        val now = System.currentTimeMillis()
        user.ids(kind, sourceId, itemId)?.let { c ->
            val ids = MediaIds(c.tmdb, c.imdb, c.trakt)
            if (!ids.isEmpty || now - c.checkedAt < RETRY_MS) return ids.takeUnless { it.isEmpty }
        }
        val (name, _, catalogYear) = row ?: return null // gone from the provider: nothing to look up by
        var year = catalogYear ?: TitleMatch.parse(name).year
        var found: MediaIds? = null
        val src = app.portal.cachedConfig?.profile?.sources?.firstOrNull { it.id == sourceId && it.type == "xtream" }
        if (src != null) {
            runCatching { app.catalog.infoIds(sourceId, itemId, src, series = kind == Kind.SERIES) }.getOrNull()?.let { info ->
                if (info.tmdb != null || info.imdb != null) found = MediaIds(info.tmdb, info.imdb)
                year = year ?: info.year
            }
        }
        if (found == null) {
            if (budget == null || budget.left <= 0) return null
            budget.left--
            found = search(kind, name, year) ?: MediaIds()
        }
        val ids = found ?: MediaIds()
        user.putIds(MediaIdsEntity(kind, sourceId, itemId, ids.tmdb, ids.imdb, ids.trakt, now))
        return ids.takeUnless { it.isEmpty }
    }

    private suspend fun search(kind: String, name: String, year: Int?): MediaIds? {
        val title = TitleMatch.parse(name).title.ifBlank { return null }
        val type = if (kind == Kind.MOVIE) "movie" else "show"
        val hits = list<TSearchHit>("/search/$type?limit=10&query=" + URLEncoder.encode(title, "UTF-8"))
        val candidates = hits.mapNotNull { h ->
            val m = h.movie ?: h.show ?: return@mapNotNull null
            TitleMatch.Candidate(m.title.orEmpty(), m.year, m.ids?.toIds())
        }
        return TitleMatch.best(name, year, candidates)?.takeUnless { it.isEmpty }
    }

    /** Catalog items carrying each of these ids: the provider's TMDB ids, then ids found earlier. */
    private suspend fun refs(kind: String, all: List<MediaIds>): List<List<Ref>> {
        if (all.isEmpty()) return emptyList()
        val tmdb = all.mapNotNull { it.tmdb }.distinct()
        val byTmdb = HashMap<String, MutableList<Ref>>()
        for (chunk in tmdb.chunked(500)) {
            if (kind == Kind.MOVIE) catalog.moviesByTmdb(chunk).forEach { byTmdb.getOrPut(it.tmdb!!) { ArrayList() } += Ref(kind, it.sourceId, it.streamId) }
            else catalog.seriesByTmdb(chunk).forEach { byTmdb.getOrPut(it.tmdb!!) { ArrayList() } += Ref(kind, it.sourceId, it.seriesId) }
        }
        val cached = ArrayList<MediaIdsEntity>()
        tmdb.chunked(300).forEach { cached += user.idsMatching(kind, it, emptyList(), emptyList()) }
        all.mapNotNull { it.imdb }.distinct().chunked(300).forEach { cached += user.idsMatching(kind, emptyList(), it, emptyList()) }
        all.mapNotNull { it.trakt }.distinct().chunked(300).forEach { cached += user.idsMatching(kind, emptyList(), emptyList(), it) }
        return all.map { ids ->
            (ids.tmdb?.let { byTmdb[it] }.orEmpty() + cached.filter { ids.matches(MediaIds(it.tmdb, it.imdb, it.trakt)) }.map { Ref(kind, it.sourceId, it.itemId) }).distinct()
        }
    }

    /**
     * Catalog items for each Trakt title: by id, else by title and year (then the ids are saved,
     * so the next sync matches by id). Title matching is capped per sync, since a long Trakt
     * history can run to thousands of titles; the rest are matched on later syncs.
     */
    private suspend fun matched(kind: String, media: List<TMedia>): List<List<Ref>> {
        val byIds = refs(kind, media.map { it.ids?.toIds() ?: MediaIds() })
        var titleLookups = MAX_TITLE_MATCHES
        val tried = HashMap<String, List<Ref>>()
        return media.mapIndexed { i, m ->
            byIds[i].ifEmpty {
                val title = m.title?.takeIf { it.isNotBlank() } ?: return@ifEmpty emptyList()
                tried.getOrPut("$title/${m.year}") {
                    if (titleLookups-- <= 0) return@ifEmpty emptyList()
                    refsByTitle(kind, title, m.year).also { found ->
                        m.ids?.toIds()?.takeUnless { it.isEmpty }?.let { ids -> found.forEach { rememberIds(it, ids) } }
                    }
                }
            }
        }
    }

    /** Last resort for watchlist items: the catalog by title (and year). */
    private suspend fun refsByTitle(kind: String, title: String, year: Int?): List<Ref> {
        // LIKE on the longest word, so punctuation differences ("Spider-Man:" vs "Spider Man") still find it.
        val word = TitleMatch.parse(title).title.split(Regex("[^\\p{L}\\p{N}]+")).maxByOrNull { it.length }?.takeIf { it.length >= 2 } ?: return emptyList()
        val rows = if (kind == Kind.MOVIE) {
            catalog.searchMovies(word, 300).map { TitleMatch.Candidate(it.name, it.year, Ref(kind, it.sourceId, it.streamId)) }
        } else {
            catalog.searchSeries(word, 300).map { TitleMatch.Candidate(it.name, it.year, Ref(kind, it.sourceId, it.seriesId)) }
        }
        return TitleMatch.catalogMatches(title, year, rows)
    }

    private suspend fun rememberIds(r: Ref, ids: MediaIds) {
        val now = System.currentTimeMillis()
        if (r.kind == Kind.MOVIE && catalog.movie(r.sourceId, r.itemId)?.tmdb != null) return
        if (r.kind == Kind.SERIES && catalog.seriesItem(r.sourceId, r.itemId)?.tmdb != null) return
        user.putIds(MediaIdsEntity(r.kind, r.sourceId, r.itemId, ids.tmdb, ids.imdb, ids.trakt, now))
    }

    private suspend fun catalogName(r: Ref): String? =
        if (r.kind == Kind.MOVIE) catalog.movie(r.sourceId, r.itemId)?.name else catalog.seriesItem(r.sourceId, r.itemId)?.name

    private suspend inline fun <reified T> list(path: String): List<T> =
        trakt.get<Array<T>>(path, java.lang.reflect.Array.newInstance(T::class.java, 0).javaClass)?.toList().orEmpty()

    companion object {
        const val START = "start"
        const val PAUSE = "pause"
        const val STOP = "stop"
        /** Saves the resume point locally only. */
        const val TICK = "tick"

        private const val MAX_SEARCHES = 25
        private const val MAX_TITLE_MATCHES = 200
        private const val RETRY_MS = 7 * 24 * 3_600_000L

        private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
        private fun parse(s: String?): Long = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
}

fun TIds.toIds() = MediaIds(tmdb = tmdb?.toString(), imdb = imdb?.takeIf { it.isNotBlank() }, trakt = trakt)

fun MediaIds.toTrakt() = TIds(trakt = trakt, imdb = imdb, tmdb = tmdb?.toLongOrNull())
