package app.familytv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.FamilyTvApp
import app.familytv.app
import app.familytv.iptv.SeriesTitles
import app.familytv.user.SeriesLanguages
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.usage.Usage
import app.familytv.user.Favourites
import app.familytv.user.FavouriteEntity
import app.familytv.user.NewEpisodeList
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Favourites" entry at the top of each section's categories. */
private const val FAV_CATEGORY = "\u0000favourites"

fun favouritesCategory(kind: String) = CategoryEntity(sourceId = "", kind = kind, categoryId = FAV_CATEGORY, name = "Favourites", sort = -1, gen = 0)

val CategoryEntity.isFavourites get() = categoryId == FAV_CATEGORY

@Composable
fun rememberFavourites(): List<FavouriteEntity> {
    val ctx = LocalContext.current
    // Each viewer has their own favourites, so the kept ones are only used again for the same viewer.
    val all by remember { ctx.app.favourites.all }.collectKept("favourites", emptyList(), ctx.app.viewers.current()?.id ?: "")
    return all
}

/** "kind/sourceId/itemId" for every favourite, to light up hearts. */
@Composable
fun rememberFavouriteKeys(favs: List<FavouriteEntity> = rememberFavourites()): Set<String> =
    remember(favs) { favs.mapTo(HashSet()) { Favourites.key(it.kind, it.sourceId, it.itemId) } }

/** Resume points and watched marks, by [app.familytv.user.ProgressKey]; a film's marks show on all its copies ([withSiblings]). */
@Composable
fun rememberProgress(): Map<String, ProgressEntity> {
    val ctx = LocalContext.current
    val rows by remember { ctx.app.userDb.user().progress() }.collectKept("progress", emptyList())
    val movies = remember(rows) { rows.filter { it.kind == ProgressKey.MOVIE }.mapTo(HashSet()) { it.key } }
    var siblings by remember { mutableStateOf(emptyMap<String, List<String>>()) }
    LaunchedEffect(movies) { siblings = ctx.app.db.movieSiblings(movies) }
    return remember(rows, siblings) { withSiblings(rows, siblings) }
}

/** The [ProgressKey]s the picked viewer played on this TV, newest first, or null when nobody is picked. */
@Composable
fun rememberSeen(): List<String>? {
    val viewers = LocalContext.current.app.viewers
    val tick by viewers.changes.collectAsState()
    return remember(tick) { viewers.seen() }
}

/** These resume points, cut to the titles [seen] lists when a viewer is picked (Continue watching is theirs alone). */
fun Collection<ProgressEntity>.playedBy(seen: List<String>?): Collection<ProgressEntity> =
    if (seen == null) this else seen.toHashSet().let { keys -> filter { it.key in keys } }

/** Adds or removes a favourite and says so with a toast (on a TV, the only feedback a long press gets). */
fun toggleFavourite(ctx: Context, kind: String, sourceId: String, itemId: String, name: String) {
    val app = ctx.app
    app.scope.launch {
        val on = app.favourites.toggle(kind, sourceId, itemId, name)
        Usage.log(if (on) Usage.Action.FAVOURITE_ADD else Usage.Action.FAVOURITE_REMOVE)
        withContext(Dispatchers.Main) { Toast.makeText(ctx.applicationContext, favouriteMessage(app, kind, on), Toast.LENGTH_SHORT).show() }
    }
}

internal fun favouriteMessage(app: FamilyTvApp, kind: String, on: Boolean) = when {
    app.favourites.viewer != null -> if (on) "Added to ${app.favourites.viewer?.name}'s Favourites" else "Removed from ${app.favourites.viewer?.name}'s Favourites"
    !on -> "Removed from Favourites"
    kind == Kind.LIVE && app.trakt.signedIn -> "Added to Favourites on this device (Trakt has no live channels)"
    kind != Kind.LIVE && app.trakt.signedIn -> "Added to Favourites and your Trakt watchlist"
    else -> "Added to Favourites"
}

// Favourites resolved to today's catalog rows, so they play like any other row. Ones the
// provider dropped, or in hidden or locked categories, are left out.

@Composable
fun rememberFavouriteChannels(favs: List<FavouriteEntity>, visible: List<CategoryEntity>): List<ChannelEntity> {
    val dao = LocalContext.current.app.db.catalog()
    return rememberResolved(favs, visible, Kind.LIVE, { dao.channel(it.sourceId, it.itemId) }) { it.sourceId to it.categoryId }
}

@Composable
fun rememberFavouriteMovies(favs: List<FavouriteEntity>, visible: List<CategoryEntity>): List<MovieEntity> {
    val dao = LocalContext.current.app.db.catalog()
    return rememberResolved(favs, visible, Kind.MOVIE, { dao.movie(it.sourceId, it.itemId) }) { it.sourceId to it.categoryId }
}

@Composable
fun rememberFavouriteSeries(favs: List<FavouriteEntity>, visible: List<CategoryEntity>): List<SeriesEntity> {
    val dao = LocalContext.current.app.db.catalog()
    return rememberResolved(favs, visible, Kind.SERIES, { dao.seriesItem(it.sourceId, it.itemId) }) { it.sourceId to it.categoryId }
}

@Composable
private fun <T> rememberResolved(
    favs: List<FavouriteEntity>,
    visible: List<CategoryEntity>,
    kind: String,
    lookup: suspend (FavouriteEntity) -> T?,
    category: (T) -> Pair<String, String>,
): List<T> {
    val key = "favourites/$kind"
    var rows by remember { mutableStateOf(Kept.get(key, favs to visible) ?: emptyList<T>()) }
    LaunchedEffect(favs, visible) {
        val ok = visible.mapTo(HashSet()) { it.sourceId to it.categoryId }
        rows = favs.filter { it.kind == kind }.mapNotNull { lookup(it) }.filter { category(it) in ok }
        Kept.put(key, favs to visible, rows)
    }
    return rows
}

/** Touch: the heart toggle on a card or row, a full 48dp target. TV uses hold-OK instead. */
@Composable
fun FavouriteButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onClick, color = Color.Transparent, shape = CircleShape, modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) { focused ->
        Box(Modifier.size(32.dp).background(Color(0x99060A14), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (on) "Remove from Favourites" else "Add to Favourites",
                tint = if (on && !focused) Tv.Accent else Tv.Text,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "NEW" (or "3 NEW") on a favourite show's poster while new episodes are waiting to be watched. */
@Composable
fun NewBadge(modifier: Modifier = Modifier, count: Int = 0) {
    Box(modifier.padding(6.dp).background(Tv.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(if (count > 1) "$count NEW" else "NEW", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tv.Bg)
    }
}

/** A small marker on artwork: a heart for favourites, a tick for watched. */
@Composable
fun MarkBadge(modifier: Modifier = Modifier, favourite: Boolean = false, watched: Boolean = false, size: Dp = 22.dp) {
    if (!favourite && !watched) return
    Box(modifier.padding(6.dp).size(size).background(if (watched) Tv.Good else Color(0xCC060A14), CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            if (watched) Icons.Filled.Check else Icons.Filled.Favorite,
            contentDescription = if (watched) "Watched" else "Favourite",
            tint = if (watched) Tv.Bg else Tv.Accent,
            modifier = Modifier.size(size * 0.65f),
        )
    }
}

// ---- shows (every language copy of a series counts as one favourite) ----

/** Every favourite series resolved to its catalog row, wherever it is listed (for hearts on grouped shows). */
@Composable
fun rememberFavouriteShowRows(favs: List<FavouriteEntity> = rememberFavourites()): List<SeriesEntity> {
    val dao = LocalContext.current.app.db.catalog()
    var rows by remember { mutableStateOf(Kept.get("favouriteShows", favs) ?: emptyList<SeriesEntity>()) }
    LaunchedEffect(favs) {
        rows = favs.filter { it.kind == Kind.SERIES }.mapNotNull { dao.seriesItem(it.sourceId, it.itemId) }
        Kept.put("favouriteShows", favs, rows)
    }
    return rows
}

/**
 * How many new episodes each show ("sourceId/seriesId") has waiting to be watched, newest first.
 * The count goes down as each one is watched (here or on Trakt), and the show drops off at zero.
 */
@Composable
fun rememberNewEpisodeKeys(progress: Map<String, ProgressEntity> = rememberProgress()): Map<String, Int> {
    val ctx = LocalContext.current
    val rows by remember { ctx.app.userDb.user().newEpisodes() }.collectKept("newEpisodes", emptyList())
    // An episode watched on any copy of the show (another language or quality) counts as watched.
    var copies by remember { mutableStateOf(Kept.get("newEpisodeCopies", rows) ?: emptyMap<String, List<Pair<String, String>>>()) }
    LaunchedEffect(rows) {
        copies = rows.associate { w -> "${w.sourceId}/${w.seriesId}" to ctx.app.db.copyIds(w.sourceId, w.seriesId) }
        Kept.put("newEpisodeCopies", rows, copies)
    }
    return remember(rows, progress, copies) {
        rows.mapNotNull { w ->
            val key = "${w.sourceId}/${w.seriesId}"
            val ids = copies[key] ?: listOf(w.sourceId to w.seriesId)
            val n = NewEpisodeList.unwatched(w) { s, e -> ids.any { (src, id) -> progress[ProgressKey.episode(src, id, s, e)]?.watched == true } }
            if (n > 0) key to n else null
        }.toMap(LinkedHashMap())
    }
}

/** Those shows as posters, newest first, minus any in hidden or locked categories. */
@Composable
fun rememberNewEpisodeShows(keys: Map<String, Int>, visible: List<CategoryEntity>): List<SeriesGroup> {
    val dao = LocalContext.current.app.db.catalog()
    var shows by remember { mutableStateOf(Kept.get("newEpisodeShows", keys to visible) ?: emptyList<SeriesGroup>()) }
    LaunchedEffect(keys, visible) {
        val ok = visible.mapTo(HashSet()) { it.sourceId to it.categoryId }
        val rows = keys.keys.mapNotNull { k -> k.split('/', limit = 2).let { (s, id) -> dao.seriesItem(s, id) } }.filter { (it.sourceId to it.categoryId) in ok }
        shows = groupSeries(rows)
        Kept.put("newEpisodeShows", keys to visible, shows)
    }
    return shows
}

fun SeriesGroup.hasNew(keys: Map<String, Int>) = keys.isNotEmpty() && copies.any { it.key in keys }

/** New episodes on any copy of the show (copies in other languages count the same episodes once). */
fun SeriesGroup.newCount(keys: Map<String, Int>) = if (keys.isEmpty()) 0 else copies.maxOf { keys[it.key] ?: 0 }

/** Whether a grouped show is a favourite: any copy of it is (same row, same TMDB id or same title). */
class ShowFavourites(private val rows: List<SeriesEntity>) {
    private val keys = rows.mapTo(HashSet()) { it.key }

    fun has(group: SeriesGroup): Boolean = group.copies.any { c -> c.key in keys || rows.any { f -> sameShow(f, c) } }

    companion object {
        fun sameShow(a: SeriesEntity, b: SeriesEntity) = SeriesTitles.sameShow(a.tmdb, a.groupKey, b.tmdb, b.groupKey)
    }
}

/**
 * Favourites a show as one: adding favourites the copy in the family's language ([preferred],
 * or the one [SeriesLanguages] picks), so Trakt matches that copy's TMDB id; removing removes
 * every favourited copy of it.
 */
fun toggleShowFavourite(ctx: Context, group: SeriesGroup, isFavourite: Boolean, preferred: SeriesEntity? = null) {
    val app = ctx.app
    Usage.log(if (!isFavourite) Usage.Action.FAVOURITE_ADD else Usage.Action.FAVOURITE_REMOVE)
    app.scope.launch {
        val copies = app.db.allCopies(group)
        if (isFavourite) {
            val dao = app.db.catalog()
            val favs = app.favourites.now().filter { it.kind == Kind.SERIES }
            for (f in favs) {
                val row = dao.seriesItem(f.sourceId, f.itemId)
                val match = copies.any { c -> c.sourceId == f.sourceId && c.seriesId == f.itemId } ||
                    (row != null && copies.any { ShowFavourites.sameShow(row, it) })
                if (match) app.favourites.toggle(Kind.SERIES, f.sourceId, f.itemId, f.name)
            }
        } else {
            val pick = preferred ?: copies.getOrNull(SeriesLanguages(ctx).choose(copies, app.quality[Kind.SERIES])) ?: group.first
            app.favourites.toggle(Kind.SERIES, pick.sourceId, pick.seriesId, pick.name)
        }
        withContext(Dispatchers.Main) { Toast.makeText(ctx.applicationContext, favouriteMessage(app, Kind.SERIES, !isFavourite), Toast.LENGTH_SHORT).show() }
    }
}
