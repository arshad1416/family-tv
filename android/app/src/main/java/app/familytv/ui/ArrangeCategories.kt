package app.familytv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.portal.Profile
import kotlin.math.roundToInt

private val KINDS = listOf(Kind.LIVE to "Live TV", Kind.MOVIE to "Movies", Kind.SERIES to "Series")
private val ROW = 52.dp

/**
 * Settings: this device's own category order per kind. Like the portal, it starts folded to one row
 * per kind with its count; OK opens one and Back folds it again. Drag a row by its handle (touch) or
 * use its arrows; the profile's order from the portal applies again after "Use the portal's order".
 */
@Composable
fun ArrangeCategoriesPanel(profile: Profile) {
    val ctx = LocalContext.current
    val touch = LocalFormFactor.current.touch
    val store = ctx.app.myCategoryOrder
    val mine by store.order.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val heads = remember { KINDS.associate { (k, _) -> k to FocusRequester() } }
    // Back folds the open kind and puts focus on its row, since the row that had it goes.
    BackHandler(enabled = open != null) {
        val k = open ?: return@BackHandler
        runCatching { heads.getValue(k).requestFocus() }
        open = null
    }
    Panel {
        Text("Arrange categories", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            (if (touch) "Open a list, then drag a category by its handle or use the arrows " else "Open a list, then use the arrows ") +
                "to put the categories in the order you like. This changes only this device.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        KINDS.forEach { (k, label) ->
            key(k) {
                // The same name can come from two lines; one row moves both.
                val names = categories(profile, k).map { it.name }.distinctBy { it.lowercase() }
                val expanded = open == k
                FocusBox(
                    onClick = { open = if (expanded) null else k },
                    modifier = Modifier.fillMaxWidth().focusRequester(heads.getValue(k)),
                    color = Tv.Raised, selected = expanded, shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${names.size}  " + if (expanded) "▴" else "▾", fontSize = 15.sp)
                    }
                }
                if (expanded) {
                    if (names.isEmpty()) Text("Nothing to arrange until the lists have loaded.", fontSize = 14.sp, color = Tv.Muted)
                    else ReorderList(names, touch) { store.set(k, it) }
                    if (mine[k] != null) TvButton("Use the portal's order", { store.reset(k) })
                }
            }
        }
    }
}

/** [names] as rows that move by drag (touch) or by their arrows; [onMove] gets the new order. */
@Composable
private fun ReorderList(names: List<String>, touch: Boolean, onMove: (List<String>) -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    var dragging by remember(names) { mutableIntStateOf(-1) }
    var offset by remember(names) { mutableFloatStateOf(0f) }
    val target = dropIndex(dragging, offset, rowPx, names.size)
    Column {
        names.forEachIndexed { i, n ->
            // While a row is dragged, the rows it passes slide over by one to show where it will land.
            val shift = when {
                dragging < 0 || i == dragging -> 0f
                i in (dragging + 1)..target -> -rowPx
                i in target until dragging -> rowPx
                else -> 0f
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW)
                    .zIndex(if (i == dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (i == dragging) offset else shift }
                    .background(if (i == dragging) Tv.Raised else Color.Transparent, RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (touch) {
                    Icon(
                        Icons.Filled.Menu, contentDescription = "Drag to move", tint = Tv.Muted,
                        modifier = Modifier.size(44.dp).padding(10.dp).pointerInput(names) {
                            detectDragGestures(
                                onDragStart = { dragging = i; offset = 0f },
                                onDragEnd = {
                                    val to = dropIndex(dragging, offset, rowPx, names.size)
                                    val from = dragging
                                    dragging = -1; offset = 0f
                                    if (from >= 0 && to != from) onMove(moved(names, from, to))
                                },
                                onDragCancel = { dragging = -1; offset = 0f },
                            ) { change, amount -> change.consume(); offset += amount.y }
                        },
                    )
                }
                Text(n, Modifier.weight(1f).padding(start = if (touch) 4.dp else 12.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Arrow("▲", enabled = i > 0) { onMove(moved(names, i, i - 1)) }
                Arrow("▼", enabled = i < names.lastIndex) { onMove(moved(names, i, i + 1)) }
            }
        }
    }
}

@Composable
private fun Arrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    FocusBox(
        onClick = { if (enabled) onClick() }, color = Color.Transparent, contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 4.dp).size(44.dp),
    ) { focused -> Text(text, fontSize = 16.sp, color = if (enabled || focused) Tv.Text else Tv.Line) }
}

/** Where a row dragged from [from] by [offset] pixels lands, for rows [rowPx] tall. */
internal fun dropIndex(from: Int, offset: Float, rowPx: Float, count: Int): Int =
    if (from < 0 || count == 0) -1 else (from + (offset / rowPx).roundToInt()).coerceIn(0, count - 1)

/** [names] with the one at [from] moved to [to]. */
internal fun moved(names: List<String>, from: Int, to: Int): List<String> =
    names.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }

/**
 * Settings: each line's package sorter (the provider's page for choosing and ordering channels)
 * as a QR code and link, for lines the portal has one for.
 */
@Composable
fun PackageSorterPanel(profile: Profile?) {
    val ctx = LocalContext.current
    val touch = LocalFormFactor.current.touch
    val links = profile?.sources.orEmpty().mapNotNull { s -> s.sorterUrl?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { s.name to it } }
    if (links.isEmpty()) return
    Panel {
        Text("Arrange your package", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            (if (touch) "Open the link" else "Scan the QR code with your phone") +
                " to choose and order the channels on your line. Then press Reload channels.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        links.forEach { (name, url) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (links.size > 1) Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val qr = remember(url) { qrImage(url, 300) }
                Image(qr, contentDescription = "QR code for the package sorter", modifier = Modifier.size(if (touch) 160.dp else 200.dp))
                // The link carries the line's private token: only the site shows, the QR holds the rest.
                Text(sorterSite(url), fontSize = 12.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (touch) TvButton("Open the sorter", { openLink(ctx, url) })
            }
        }
    }
}

/** A sorter link as shown on screen: its site only ("sorter.example.com/…"), never the token in its path or query. */
internal fun sorterSite(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "$it/…" } ?: "Link in the QR code"

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
