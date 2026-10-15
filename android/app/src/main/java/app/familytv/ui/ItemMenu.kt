package app.familytv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A short list of actions for one movie or episode, opened by hold-OK or Menu (TV) or a long press (touch). */
class ItemMenu(val title: String, val actions: List<Pair<String, () -> Unit>>)

/** Opens an [ItemMenu] over whatever screen is showing; [Host] draws it. */
class ItemMenus {
    var open by mutableStateOf<ItemMenu?>(null)

    @Composable
    fun Host() {
        val menu = open ?: return
        val close = { open = null }
        Dialog(onDismissRequest = close) {
            Column(
                Modifier.widthIn(min = 280.dp, max = 460.dp).background(Tv.Raised, Tv.Shape).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(menu.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val first = remember(menu) { FocusRequester() }
                menu.actions.forEachIndexed { i, (label, action) ->
                    TvButton(label, { close(); action() }, if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth())
                }
                TvButton("Close", close, Modifier.fillMaxWidth())
                LaunchedEffect(menu) { runCatching { first.requestFocus() } }
            }
        }
    }
}

val LocalItemMenus = compositionLocalOf { ItemMenus() }

/** Favourite and watched actions for a movie. */
fun movieMenu(ctx: Context, m: MovieEntity, favourite: Boolean, watched: Boolean) = ItemMenu(
    m.name,
    listOf(
        (if (watched) "Mark as unwatched" else "✓  Mark as watched") to { markMovie(ctx, m, !watched) },
        (if (favourite) "♥  Remove from Favourites" else "♡  Add to Favourites") to { toggleFavourite(ctx, Kind.MOVIE, m.sourceId, m.streamId, m.name) },
    ),
)

fun markMovie(ctx: Context, m: MovieEntity, watched: Boolean) {
    val app = ctx.app
    app.scope.launch {
        app.watched.movie(m.sourceId, m.streamId, watched)
        toast(ctx, if (watched) "Marked as watched" else "Marked as unwatched")
    }
}

suspend fun toast(ctx: Context, text: String) = withContext(Dispatchers.Main) {
    Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show()
}
