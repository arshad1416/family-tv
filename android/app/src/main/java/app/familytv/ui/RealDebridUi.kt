package app.familytv.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.debrid.RdCopy
import app.familytv.debrid.RdIndex
import app.familytv.debrid.RdResume
import app.familytv.debrid.RealDebridException
import app.familytv.trakt.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The profile's Real-Debrid files; empty without an account, so lookups simply find nothing. */
@Composable
fun rememberRdIndex(): RdIndex {
    val index by LocalContext.current.app.realDebrid.index.collectAsState()
    return index
}

/** "RD": this movie or episode is also in the Real-Debrid account. */
@Composable
fun RdBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(6.dp).background(Color(0xE61A2A4A), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("RD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8FB4FF))
    }
}

/** In the player, on a Real-Debrid copy: [text] says why, and OK (or a tap) goes back to the TV stream. */
@Composable
fun RdSwitchNotice(text: String, touch: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tap = if (touch) Modifier.clickable(onClick = onBack) else Modifier
    Row(
        modifier.widthIn(max = 720.dp).background(Color(0xF00E1424), RoundedCornerShape(14.dp)).then(tap).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(10.dp).background(Tv.Good, CircleShape))
        Text(
            buildAnnotatedString {
                append("$text  ")
                withStyle(SpanStyle(color = Tv.Muted)) { append(if (touch) "Tap for the TV stream" else "OK for the TV stream") }
            },
            fontSize = 18.sp,
        )
    }
}

/**
 * Plays the Real-Debrid copy of [item]. The player treats it as the catalog title, so the resume
 * point and Trakt are shared with the TV stream.
 */
suspend fun playFromRealDebrid(ctx: Context, copy: RdCopy, title: String, item: PlayItem) = playRd(ctx, copy, title) { item.putInto(it) }

/** Plays a Real-Debrid title the catalog doesn't have; its resume spot stays on this TV ([RdResume]). */
suspend fun playRdOnly(ctx: Context, copy: RdCopy, title: String, resumeKey: String) =
    playRd(ctx, copy, title) { it.putExtra(RdResume.EXTRA_KEY, resumeKey) }

private suspend fun playRd(ctx: Context, copy: RdCopy, title: String, extras: (Intent) -> Intent) {
    val url = try {
        ctx.app.realDebrid.streamUrl(copy)
    } catch (e: RealDebridException) {
        return toast(ctx, e.message ?: "Real-Debrid isn't answering.")
    } catch (e: Exception) {
        return toast(ctx, "Couldn't reach Real-Debrid. Check the internet connection.")
    }
    withContext(Dispatchers.Main) {
        val i = Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_REAL_DEBRID, true)
        ctx.startActivity(extras(i))
    }
}
