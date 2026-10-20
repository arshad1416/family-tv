package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.familytv.data.Kind
import app.familytv.perf.Perf
import app.familytv.portal.Profile
import java.util.Calendar

/** The tabs the Home page leads to, with the line under each tile's name. */
private val TILES = listOf(
    GlassTab.Live to "Channels and the TV guide",
    GlassTab.Movies to "Films",
    GlassTab.Series to "Shows and new episodes",
    GlassTab.Sports to "Games and sports channels",
)

/**
 * The TV home's first tab: a greeting, one big tile per tab (OK opens it), and the channels this
 * profile watched last. Back from any other tab comes here; Back here asks before leaving.
 */
@Composable
internal fun HomeTab(profile: Profile, viewer: String?, onOpen: (GlassTab) -> Unit) {
    // The first frame with the tiles on it ends a cold start (see Perf).
    LaunchedEffect(Unit) { withFrameNanos { }; Perf.firstScreen() }
    val live = categories(profile, Kind.LIVE, liveTv = true)
    val hello = remember { greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    Column(Modifier.fillMaxSize().padding(horizontal = FOCUS_ROOM), verticalArrangement = Arrangement.spacedBy(22.gx)) {
        Text(listOfNotNull(hello, viewer).joinToString(", "), fontSize = 30.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().height(220.gx), horizontalArrangement = Arrangement.spacedBy(20.gx)) {
            TILES.forEachIndexed { i, (tab, line) -> HomeTile(tab, line, entryPoint(i == 0).weight(1f).fillMaxHeight()) { onOpen(tab) } }
        }
        RecentStrip(profile, live)
    }
}

/** "Good morning" before noon, "Good afternoon" until 6 pm, then "Good evening". */
internal fun greeting(hour: Int): String = when {
    hour in 5..11 -> "Good morning"
    hour in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun HomeTile(tab: GlassTab, line: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.gx)
    FocusBox(onClick = onClick, outline = true, scale = 1.04f, color = Color.Transparent, shape = shape, modifier = modifier) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tab.glow.copy(alpha = 0.7f), Color(0xFF141A24)))).border(1.dp, Color(0x24FFFFFF), shape))
        OnDarkArt {
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.gx, vertical = 18.gx)) {
                Text(tab.label, fontSize = 24.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(line, fontSize = 15.gsp, color = Glass.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.gx))
            }
        }
    }
}
