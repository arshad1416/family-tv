package app.familytv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.tv.material3.Icon
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.user.Favourites
import app.familytv.user.FavouriteEntity
import app.familytv.user.ProgressEntity
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
    val all by remember { ctx.app.favourites.all }.collectAsState(initial = emptyList())
    return all
}

/** "kind/sourceId/itemId" for every favourite, to light up hearts. */
@Composable
fun rememberFavouriteKeys(favs: List<FavouriteEntity> = rememberFavourites()): Set<String> =
    remember(favs) { favs.mapTo(HashSet()) { Favourites.key(it.kind, it.sourceId, it.itemId) } }

/** Resume points and watched marks, by [app.familytv.user.ProgressKey]. */
@Composable
fun rememberProgress(): Map<String, ProgressEntity> {
    val ctx = LocalContext.current
    val rows by remember { ctx.app.userDb.user().progress() }.collectAsState(initial = emptyList())
    return remember(rows) { rows.associateBy { it.key } }
}

/** Adds or removes a favourite and says so with a toast (on a TV, the only feedback a long press gets). */
fun toggleFavourite(ctx: Context, kind: String, sourceId: String, itemId: String, name: String) {
    val app = ctx.app
    app.scope.launch {
        val on = app.favourites.toggle(kind, sourceId, itemId, name)
        val text = when {
            !on -> "Removed from Favourites"
            kind == Kind.LIVE && app.trakt.signedIn -> "Added to Favourites on this device (Trakt has no live channels)"
            kind != Kind.LIVE && app.trakt.signedIn -> "Added to Favourites and your Trakt watchlist"
            else -> "Added to Favourites"
        }
        withContext(Dispatchers.Main) { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
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
    var rows by remember { mutableStateOf(emptyList<T>()) }
    LaunchedEffect(favs, visible) {
        val ok = visible.mapTo(HashSet()) { it.sourceId to it.categoryId }
        rows = favs.filter { it.kind == kind }.mapNotNull { lookup(it) }.filter { category(it) in ok }
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
