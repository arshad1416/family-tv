package app.familytv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.MovieEntity
import app.familytv.portal.Profile
import app.familytv.trailer.openTrailer
import kotlinx.coroutines.launch

/**
 * What a movie card's Trailer button does: look the trailer up only now (never while browsing,
 * since providers throttle bursts of API calls), then open it or say there is none.
 */
@Composable
fun movieTrailerAction(profile: Profile, m: MovieEntity): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val id = ctx.app.extras.movieTrailer(profile, m)
            if (id != null) openTrailer(ctx, id)
            else Toast.makeText(ctx.applicationContext, "No trailer found for this movie", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * "▶ Trailer": opens the YouTube trailer. A 48dp target on touch, a focusable pill on TV.
 * [compact] draws a small pill inside the (still 48dp) target, for a poster's corner.
 */
@Composable
fun TrailerButton(youtubeId: String, modifier: Modifier = Modifier, label: String = "Trailer", compact: Boolean = false) {
    val ctx = LocalContext.current
    TrailerButton({ openTrailer(ctx, youtubeId) }, modifier, label, compact)
}

@Composable
fun TrailerButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Trailer", compact: Boolean = false) {
    val touch = LocalFormFactor.current.touch
    FocusBox(
        onClick = onClick,
        color = if (compact) Color.Transparent else Tv.Raised,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.heightIn(min = if (touch) 48.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { focused ->
        Row(
            (if (compact) Modifier.background(Color(0xCC060A14), RoundedCornerShape(14.dp)) else Modifier)
                .padding(horizontal = if (compact) 10.dp else 18.dp, vertical = if (compact) 5.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (focused) Tv.Text else Tv.Accent, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
            Text(label, fontSize = if (compact) 13.sp else 16.sp, color = Tv.Text)
        }
    }
}
