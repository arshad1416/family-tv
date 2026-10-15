package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.trakt.PlayItem
import app.familytv.user.Favourites
import app.familytv.user.ProgressKey

/**
 * What holding OK (or Menu, or a long press) on a movie offers: favourite it, or mark it
 * watched. Any movie card opens it by setting [open]; [MainActivity] draws it over every screen.
 */
object MovieActions {
    var open by mutableStateOf<MovieEntity?>(null)
}

@Composable
fun MovieActionsDialog() {
    val m = MovieActions.open ?: return
    val ctx = LocalContext.current
    val favourite = Favourites.key(Kind.MOVIE, m.sourceId, m.streamId) in rememberFavouriteKeys()
    val watched = rememberProgress()[ProgressKey.movie(m.sourceId, m.streamId)]?.watched == true
    val close = { MovieActions.open = null }
    Dialog(onDismissRequest = close) {
        Column(
            Modifier.widthIn(max = 480.dp).background(Tv.Raised, Tv.Shape).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(m.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val first = remember { FocusRequester() }
            TvButton(
                if (watched) "Mark as not watched" else "✓  Mark as watched",
                { close(); setWatched(ctx, listOf(PlayItem.movie(m.sourceId, m.streamId, m.name)), !watched, m.name) },
                Modifier.fillMaxWidth().focusRequester(first),
            )
            TvButton(
                if (favourite) "♥  Remove from Favourites" else "♡  Add to Favourites",
                { close(); toggleFavourite(ctx, Kind.MOVIE, m.sourceId, m.streamId, m.name) },
                Modifier.fillMaxWidth(),
            )
            TvButton("Cancel", close, Modifier.fillMaxWidth())
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}
