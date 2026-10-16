package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CatalogDao
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.portal.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

private val SECTIONS = listOf(Kind.LIVE to Page.Live, Kind.MOVIE to Page.Movies, Kind.SERIES to Page.Series)
private const val ADULT_CONTENT = "Adult Content"
/** The Sports tile's value for `kind`: live channels from every sports category, by sport. */
private const val SPORTS = "sports"

/**
 * Pick Live TV, Movies or Series, then one of its categories. Each section ends with Adult Content,
 * holding the adult categories the other pages never show; it asks for the profile's parental PIN
 * each time it opens (and doesn't when the profile has none). Sports lists the live channels of
 * the sports categories by sport ([groupBySport]). Back steps up one level.
 */
@Composable
fun CategoriesScreen(vm: MainViewModel, profile: Profile) {
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var catKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sport by rememberSaveable { mutableStateOf<Sport?>(null) }
    // Not saveable: leaving the page locks Adult Content again.
    var adult by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val nav = rememberSeriesNav()
    val ctx = LocalContext.current
    val listKind = if (kind == SPORTS) Kind.LIVE else kind
    val all by remember(listKind) { listKind?.let { ctx.app.db.catalog().categories(it) } ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val mine by ctx.app.myCategoryOrder.order.collectAsState()
    val normal = remember(all, profile, mine) { visibleCategories(all, profile, mine[listKind].orEmpty()) }
    var sports by remember(kind, normal) { mutableStateOf<Map<Sport, List<ChannelEntity>>?>(null) }
    LaunchedEffect(kind, normal) {
        if (kind == SPORTS) sports = withContext(Dispatchers.IO) { sportsChannels(ctx.app.db.catalog(), normal) }
    }
    val adults = remember(all, profile, mine) { adultCategories(all, profile, mine[kind].orEmpty()) }
    val cat = (if (adult) adults else normal).firstOrNull { it.chipKey == catKey }
    val section = SECTIONS.firstOrNull { it.first == kind }?.second?.label ?: SPORTS_LABEL.takeIf { kind == SPORTS }
    // On TV, each step down or back up moves focus to the new level's first tile, once it's there.
    val entry = pageEntry()
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(kind, cat, adult, asking, sport) {
        if (!moved) { moved = true; return@LaunchedEffect }
        repeat(20) { delay(50); if (entry.attached > 0) return@LaunchedEffect entry.focus() }
    }

    nav.Host(vm, profile) {
        BackHandler(enabled = kind != null) {
            when {
                cat != null -> catKey = null
                sport != null -> sport = null
                asking -> asking = false
                adult -> adult = false
                else -> kind = null
            }
        }
        Column {
            val title = listOfNotNull(section, ADULT_CONTENT.takeIf { adult || asking }, cat?.name, sport?.label).joinToString("  ›  ")
            if (title.isNotEmpty()) Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
            when {
                section == null -> Tiles(
                    SECTIONS.map { (k, p) -> Tile(p.label, p.icon) { kind = k; adult = false; catKey = null } } +
                        Tile(SPORTS_LABEL, Icons.Filled.Star) { kind = SPORTS; adult = false; catKey = null; sport = null },
                )
                kind == SPORTS -> {
                    val groups = sports
                    val list = sport?.let { groups?.get(it) }
                    when {
                        groups == null -> EmptyState("Finding the sports channels…")
                        list != null -> ChannelPosters(list, "sport/${sport!!.name}")
                        groups.isEmpty() -> EmptyState("No sports channels on this line.")
                        else -> Tiles(groups.map { (s, chs) -> Tile("${s.label} · ${chs.size}") { sport = s } })
                    }
                }
                cat != null -> CategoryPosters(profile, cat, nav)
                asking -> PinPrompt(profile) { asking = false; adult = true }
                else -> {
                    val list = if (adult) adults else normal
                    val tiles = list.map { c -> Tile(c.name) { catKey = c.chipKey } } +
                        listOfNotNull(Tile(ADULT_CONTENT, Icons.Filled.Lock) {
                            if (profile.parentalPin.isNullOrEmpty()) adult = true else asking = true
                        }.takeIf { !adult && adults.isNotEmpty() })
                    if (tiles.isEmpty()) EmptyState(if (adult) "No adult categories on this line." else "No categories on this line.")
                    else Tiles(tiles)
                }
            }
        }
    }
}

private const val SPORTS_LABEL = "Sports"

/** The live channels of the sports categories among [categories] (already filtered and ordered), by sport. */
private suspend fun sportsChannels(dao: CatalogDao, categories: List<CategoryEntity>): Map<Sport, List<ChannelEntity>> {
    val picked = categories.filter { Sport.isSportsCategory(it.name) }
    val channels = picked.groupBy { it.sourceId }.flatMap { (src, cats) ->
        cats.map { it.categoryId }.distinct().chunked(500).flatMap { dao.channelsIn(src, it) }
    }
    return groupBySport(picked, channels)
}

private class Tile(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

@Composable
private fun Tiles(tiles: List<Tile>) {
    val phone = LocalFormFactor.current == FormFactor.Phone
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (phone) 150.dp else 220.dp),
        contentPadding = PaddingValues(start = FOCUS_ROOM, top = FOCUS_ROOM, end = FOCUS_ROOM, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(tiles, key = { i, t -> "$i/${t.label}" }) { i, t ->
            FocusBox(onClick = t.onClick, modifier = entryPoint(i == 0).fillMaxWidth().height(if (phone) 64.dp else 72.dp), contentAlignment = Alignment.CenterStart) { _ ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)) }
                    Text(t.label, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The parental PIN pad; [onOk] runs once the right PIN is entered. */
@Composable
internal fun PinPrompt(profile: Profile, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Enter the parental PIN", fontSize = 16.sp, color = Tv.Muted)
        Text(if (pin.isEmpty()) "PIN" else "•".repeat(pin.length), fontSize = 20.sp)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "OK")).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, k ->
                    FocusBox(
                        onClick = {
                            when (k) {
                                "C" -> pin = ""
                                "OK" -> if (pinMatches(profile, pin)) onOk() else { wrong = true; pin = "" }
                                else -> if (pin.length < 8) { pin += k; wrong = false }
                            }
                        },
                        color = Tv.Raised, contentAlignment = Alignment.Center,
                        modifier = entryPoint(r == 0 && c == 0).width(68.dp).height(48.dp),
                    ) { Text(k, fontSize = 18.sp) }
                }
            }
        }
        if (wrong) Text("Wrong PIN", color = Tv.Bad)
    }
}

/** True when [pin] is the profile's parental PIN, or the profile has none. */
fun pinMatches(profile: Profile, pin: String): Boolean = profile.parentalPin.isNullOrEmpty() || profile.parentalPin == pin
