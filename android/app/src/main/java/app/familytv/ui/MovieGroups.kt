package app.familytv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.familytv.app
import app.familytv.data.AppDb
import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.Names
import app.familytv.iptv.MovieTitles
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.groupKey
import app.familytv.iptv.language
import app.familytv.iptv.releaseYear
import app.familytv.portal.Profile
import app.familytv.trakt.ProgressRules
import app.familytv.usage.Usage
import app.familytv.user.Favourites
import app.familytv.user.MovieLanguages
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val MovieEntity.key get() = "$sourceId/$streamId"

/** (sourceId, categoryId) of each category, the form [allCopies] filters by. */
fun categoryKeys(cats: List<CategoryEntity>): Set<Pair<String, String>> = cats.mapTo(HashSet()) { it.sourceId to it.categoryId }

/**
 * A film as the browse pages list it: every language (and quality) copy of it under one poster.
 * [key] is [MovieTitles.group]'s key, stable within a list and used to refocus the poster.
 */
class MovieGroup(val key: String, val copies: List<MovieEntity>) {
    val first get() = copies.first()
    val title: String by lazy { Names.clean(SeriesTitles.parse(first.name).title) }
    val poster: String? get() = copies.firstNotNullOfOrNull { it.poster }
    val year: Int? get() = copies.firstNotNullOfOrNull { it.releaseYear }

    fun favourite(favKeys: Set<String>) = copies.any { Favourites.key(Kind.MOVIE, it.sourceId, it.streamId) in favKeys }

    /** A watched copy's progress, else the copy watched most recently. */
    fun progressIn(progress: Map<String, ProgressEntity>): ProgressEntity? {
        val rows = copies.mapNotNull { progress[ProgressKey.movie(it.sourceId, it.streamId)] }
        return rows.firstOrNull { it.watched } ?: rows.maxByOrNull { it.updatedAt }
    }
}

fun groupMovies(rows: List<MovieEntity>): List<MovieGroup> =
    MovieTitles.group(rows, { it.tmdb }, { it.groupKey }, { it.releaseYear }).map { (k, v) -> MovieGroup(k, v) }

/**
 * Every copy of [group]'s film the family may see: its own, plus those in [visible] categories
 * (hidden and locked ones stay hidden), sorted by language.
 */
suspend fun AppDb.allCopies(group: MovieGroup, visible: Set<Pair<String, String>>): List<MovieEntity> {
    val keys = group.copies.map { it.groupKey }.filter { it.isNotEmpty() }.distinct()
    val tmdb = group.copies.mapNotNull { it.tmdb }.distinct()
    val more = catalog().movieCopies(keys, tmdb).filter { (it.sourceId to it.categoryId) in visible }
    val rows = (group.copies + more).distinctBy { it.key }
    // Regroup, so a same-titled but different film (another TMDB id, or a remake) is left out.
    val anchor = group.first.key
    val copies = groupMovies(rows).firstOrNull { g -> g.copies.any { it.key == anchor } }?.copies ?: group.copies
    return copies.sortedWith(compareBy({ SeriesTitles.languageName(it.language) }, { it.key }))
}

/**
 * For each film in [keys] (movie [ProgressKey]s), the keys of its other copies in the catalog: another TV, or the Rust
 * app, may have played a copy in another language or from another line, which this page's poster doesn't list.
 */
suspend fun AppDb.movieSiblings(keys: Collection<String>): Map<String, List<String>> {
    val dao = catalog()
    val played = keys.mapNotNull { k -> k.split('/', limit = 3).takeIf { it.size == 3 && it[0] == "m" }?.let { dao.movie(it[1], it[2]) } }
    if (played.isEmpty()) return emptyMap()
    val titles = played.map { it.groupKey }.filter { it.isNotEmpty() }.distinct()
    val tmdb = played.mapNotNull { it.tmdb }.distinct()
    // Inside SQLite's limit on query arguments.
    val more = titles.chunked(400).flatMap { dao.movieCopies(it, emptyList()) } + tmdb.chunked(400).flatMap { dao.movieCopies(emptyList(), it) }
    val playedKeys = played.mapTo(HashSet()) { ProgressKey.movie(it.sourceId, it.streamId) }
    return groupMovies((played + more).distinctBy { it.key }).flatMap { g ->
        val all = g.copies.map { ProgressKey.movie(it.sourceId, it.streamId) }
        if (all.size < 2) emptyList() else all.filter { it in playedKeys }.map { k -> k to all.filter { it != k } }
    }.toMap()
}

/**
 * Progress by key, with each film's marks on its other copies ([siblings]): a copy with no row of its own takes the
 * film's latest, and a watched copy makes the whole film watched, so its poster shows the check and it leaves
 * Continue watching (round 22). Rows in the database are unchanged.
 */
internal fun withSiblings(rows: List<ProgressEntity>, siblings: Map<String, List<String>>): Map<String, ProgressEntity> {
    val own = rows.associateBy { it.key }
    if (siblings.isEmpty()) return own
    val out = HashMap(own)
    for ((key, others) in siblings) {
        val r = own[key] ?: continue
        for (s in others) {
            val cur = out[s]
            out[s] = when {
                cur == null -> r
                cur.watched -> cur
                r.watched -> r
                own[s] != null -> cur
                r.updatedAt > cur.updatedAt -> r
                else -> cur
            }
        }
    }
    return out
}

/** The copy OK plays: one already part-watched, else the one in the family's language, nearest Settings' Stream quality. */
fun pickCopy(ctx: Context, copies: List<MovieEntity>, progress: Map<String, ProgressEntity>): MovieEntity {
    val started = copies.mapNotNull { c -> progress[ProgressKey.movie(c.sourceId, c.streamId)]?.takeIf { !it.watched && ProgressRules.started(it) }?.let { c to it } }
    return started.maxByOrNull { it.second.updatedAt }?.first
        ?: copies.getOrNull(MovieLanguages(ctx).choose(copies, ctx.app.quality[Kind.MOVIE])) ?: copies.first()
}

suspend fun playMovie(ctx: Context, profile: Profile, group: MovieGroup, visible: Set<Pair<String, String>>, progress: Map<String, ProgressEntity>) {
    playMovieNow(ctx, profile, pickCopy(ctx, ctx.app.db.allCopies(group, visible), progress))
}

/**
 * A movie poster: OK opens its details page (or plays it, outside a [SeriesNav.Host]), hold OK
 * (or Menu) opens its menu; on touch it carries the Trailer button, on TV a 4K/HDR badge. In
 * Continue watching ([continuing]) it shows the full title and the time left.
 */
@Composable
internal fun MovieCard(
    g: MovieGroup,
    modifier: Modifier = Modifier,
    width: Dp? = 140.dp,
    profile: Profile,
    visible: Set<Pair<String, String>>,
    favKeys: Set<String>,
    progress: Map<String, ProgressEntity>,
    continuing: Boolean = false,
    onOpen: () -> Unit = {},
    onFocus: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val nav = LocalSeriesNav.current
    val menus = LocalItemMenus.current
    val scope = rememberCoroutineScope()
    // Only the Trailer button (touch screens) needs the copy, so the TV never picks one while scrolling.
    val shown = remember(g) { lazy { pickCopy(ctx, g.copies, emptyMap()) } }
    val badge = remember(g) { SeriesTitles.qualityBadge(g.copies.map { it.name }) }
    val p = g.progressIn(progress)
    PosterCard(
        g.title, g.poster, modifier, width = width,
        favourite = g.favourite(favKeys),
        onFavourite = { toggleMovieFavourite(ctx, g, visible, favKeys) },
        watched = p?.watched == true, progress = ProgressRules.bar(p),
        onTrailer = movieTrailerAction(profile) { shown.value },
        onMenu = { scope.launch { menus.open = movieMenu(ctx, profile, g, ctx.app.db.allCopies(g, visible), favKeys, progress, scope) } },
        onFocus = onFocus,
        badge = badge, caption = if (continuing) ProgressRules.timeLeft(p) else null, fullName = continuing,
    ) {
        onOpen()
        if (nav != null) nav.movie = g to visible else scope.launch { playMovie(ctx, profile, g, visible, progress) }
    }
}

/**
 * A film's actions: one "Play in …" per language when there are several (picking one remembers
 * it for this film), then watched and favourite.
 */
fun movieMenu(
    ctx: Context,
    profile: Profile,
    g: MovieGroup,
    copies: List<MovieEntity>,
    favKeys: Set<String>,
    progress: Map<String, ProgressEntity>,
    scope: CoroutineScope,
): ItemMenu {
    val pick = pickCopy(ctx, copies, progress)
    val all = MovieGroup(g.key, copies)
    val watched = all.progressIn(progress)?.watched == true
    val favourite = all.favourite(favKeys)
    return ItemMenu(
        listOfNotNull(g.title, g.year?.toString()).joinToString("  ·  "),
        buildList {
            if (copies.size > 1) {
                val labels = SeriesTitles.variantLabels(copies.map { it.language to SeriesTitles.parse(it.name).tags })
                copies.forEachIndexed { i, c ->
                    add("▶  Play in ${labels[i]}" to {
                        MovieLanguages(ctx).remember(copies, c.language)
                        scope.launch { playMovieNow(ctx, profile, c) }
                    })
                }
            }
            add((if (watched) "Mark as unwatched" else "✓  Mark as watched") to { markMovie(ctx, copies, pick, !watched, progress) })
            add((if (favourite) "♥  Remove from Favourites" else "♡  Add to Favourites") to { toggleMovieFavourite(ctx, all, emptySet(), favKeys, pick) })
        },
    )
}

/** Marks the copy that would play; unmarking clears every watched copy, so the film reads unwatched. */
private fun markMovie(ctx: Context, copies: List<MovieEntity>, pick: MovieEntity, watched: Boolean, progress: Map<String, ProgressEntity>) {
    val app = ctx.app
    app.scope.launch {
        val targets = if (watched) listOf(pick) else copies.filter { progress[ProgressKey.movie(it.sourceId, it.streamId)]?.watched == true }.ifEmpty { listOf(pick) }
        targets.forEach { app.watched.movie(it.sourceId, it.streamId, watched) }
        Usage.log(Usage.Action.MARK_WATCHED, if (watched) 1 else 0)
        toast(ctx, if (watched) "Marked as watched" else "Marked as unwatched")
    }
}

/**
 * Favourites a film as one: adding favourites the copy in the family's language ([preferred], or
 * the one [MovieLanguages] picks); removing removes every favourited copy of it.
 */
internal fun toggleMovieFavourite(ctx: Context, g: MovieGroup, visible: Set<Pair<String, String>>, favKeys: Set<String>, preferred: MovieEntity? = null) {
    val app = ctx.app
    app.scope.launch {
        val copies = app.db.allCopies(g, visible)
        val on = copies.filter { Favourites.key(Kind.MOVIE, it.sourceId, it.streamId) in favKeys }
        Usage.log(if (on.isEmpty()) Usage.Action.FAVOURITE_ADD else Usage.Action.FAVOURITE_REMOVE)
        if (on.isNotEmpty()) on.forEach { app.favourites.toggle(Kind.MOVIE, it.sourceId, it.streamId, it.name) }
        else (preferred ?: pickCopy(ctx, copies, emptyMap())).let { app.favourites.toggle(Kind.MOVIE, it.sourceId, it.streamId, it.name) }
        toast(ctx, favouriteMessage(app, Kind.MOVIE, on.isEmpty()))
    }
}
