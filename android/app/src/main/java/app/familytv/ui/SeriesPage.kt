package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.AppDb
import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.data.SeriesEntity
import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.Streams
import app.familytv.iptv.XtreamParser
import app.familytv.portal.Profile
import app.familytv.trailer.SeasonExtras
import app.familytv.trakt.PlayItem
import app.familytv.trakt.ProgressRules
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import app.familytv.user.SeriesLanguages
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val SeriesEntity.key get() = "$sourceId/$seriesId"

/** The title key this copy groups under (rows saved before titleKey existed fall back to parsing). */
val SeriesEntity.groupKey get() = titleKey.ifEmpty { SeriesTitles.parse(name).key }

/**
 * A show as the browse pages list it: every language (and quality) copy of it under one poster.
 * [key] is [SeriesTitles.group]'s key, stable within a list and used to refocus the poster.
 */
class SeriesGroup(val key: String, val copies: List<SeriesEntity>) {
    val first get() = copies.first()
    val title: String get() = SeriesTitles.parse(first.name).title
    val poster: String? get() = copies.firstNotNullOfOrNull { it.poster }
    val plot: String? get() = copies.firstNotNullOfOrNull { it.plot }
}

fun groupSeries(rows: List<SeriesEntity>): List<SeriesGroup> =
    SeriesTitles.group(rows, { it.tmdb }, { it.groupKey }).map { (k, v) -> SeriesGroup(k, v) }

/** Every copy of [group]'s show in the catalog, whichever category or source lists it. */
suspend fun AppDb.allCopies(group: SeriesGroup): List<SeriesEntity> {
    val keys = group.copies.map { it.groupKey }.filter { it.isNotEmpty() }.distinct()
    val tmdb = group.copies.mapNotNull { it.tmdb }.distinct()
    val rows = (group.copies + catalog().seriesCopies(keys, tmdb)).distinctBy { it.key }
    // Regroup, so a same-titled but different show (another TMDB id) is left out.
    val anchor = group.first.key
    return groupSeries(rows).firstOrNull { g -> g.copies.any { it.key == anchor } }?.copies ?: group.copies
}

/** (sourceId, seriesId) of every copy of a show, itself first; just itself when the catalog doesn't list it. */
suspend fun AppDb.copyIds(sourceId: String, seriesId: String): List<Pair<String, String>> {
    val row = catalog().seriesItem(sourceId, seriesId) ?: return listOf(sourceId to seriesId)
    return (listOf(row) + allCopies(SeriesGroup(row.key, listOf(row)))).map { it.sourceId to it.seriesId }.distinct()
}

/** Which show has its page open, and which poster to refocus when it closes. */
class SeriesNav {
    var open by mutableStateOf<SeriesGroup?>(null)
    var back by mutableStateOf<String?>(null)
    var refocus = false
}

@Composable
fun rememberSeriesNav() = remember { SeriesNav() }

/** Shows the open series' page in place of [content]; Back returns to the poster that opened it. */
@Composable
fun SeriesNav.Host(vm: MainViewModel, profile: Profile, content: @Composable () -> Unit) {
    val entry = pageEntry()
    val visible = categories(profile, Kind.SERIES)
    val o = open
    if (o != null) {
        SeriesPage(profile, o, visible) { back = o.key; refocus = true; open = null }
    } else {
        content()
        LaunchedEffect(Unit) {
            if (!refocus) return@LaunchedEffect
            refocus = false
            delay(80)
            // The poster may not be composed any more (its row scrolled away); fall back to the page's entry.
            if (entry.attached == 0) { back = null; delay(80) }
            entry.focus()
            back = null
        }
    }
}

/**
 * One show: language picker (when the provider has it in 2+ languages), description with
 * More/Less, seasons as chips and that season's episodes with stills and synopses. TV shows the
 * focused episode in a preview column and OK plays it; touch opens an episode sheet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesPage(profile: Profile, group: SeriesGroup, visible: List<CategoryEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val form = LocalFormFactor.current
    val phone = form == FormFactor.Phone
    val languages = remember { SeriesLanguages(ctx) }
    BackHandler(onBack = onBack)

    // Every copy the family may see (hidden and locked categories stay hidden), one per language.
    val visibleKeys = remember(visible) { visible.mapTo(HashSet()) { it.sourceId to it.categoryId } }
    var copies by remember(group.key) { mutableStateOf(group.copies) }
    LaunchedEffect(group.key, visibleKeys) {
        val shown = group.copies.mapTo(HashSet()) { it.key }
        copies = app.db.allCopies(group)
            .filter { it.key in shown || (it.sourceId to it.categoryId) in visibleKeys }
            .sortedWith(compareBy({ SeriesTitles.languageName(it.lang ?: SeriesTitles.ORIGINAL) }, { it.key }))
    }
    var chosenKey by rememberSaveable(group.key) { mutableStateOf<String?>(null) }
    val progress = rememberProgress()
    val quality by app.quality.flow(Kind.SERIES).collectAsState()
    // The copy already being watched, else the family's language nearest Settings' Stream quality.
    val defaultIndex = remember(copies, progress, quality) {
        val watching = progress.values.filter { it.kind == ProgressKey.EPISODE }.sortedByDescending { it.updatedAt }
            .firstNotNullOfOrNull { p -> copies.indexOfFirst { it.sourceId == p.sourceId && it.seriesId == p.itemId }.takeIf { it >= 0 } }
        watching ?: languages.choose(copies, quality).coerceAtLeast(0)
    }
    val v = copies.firstOrNull { it.key == chosenKey } ?: copies.getOrNull(defaultIndex) ?: group.first
    val labels = remember(copies) {
        SeriesTitles.variantLabels(copies.map { (it.lang ?: SeriesTitles.ORIGINAL) to SeriesTitles.parse(it.name).tags })
    }

    var info by remember(v.key) { mutableStateOf<XtreamParser.SeriesInfo?>(null) }
    LaunchedEffect(v.key) {
        val src = profile.sources.firstOrNull { it.id == v.sourceId && it.type == "xtream" }
        info = if (src == null) XtreamParser.SeriesInfo(emptyList(), null, null)
        else runCatching { app.catalog.seriesInfo(v.sourceId, v.seriesId, src) }.getOrDefault(XtreamParser.SeriesInfo(emptyList(), null, null))
    }
    fun progressOf(e: XtreamParser.Episode): ProgressEntity? = progress[ProgressKey.episode(v.sourceId, v.seriesId, e.season, e.episode)]

    val episodes = info?.episodes.orEmpty()
    val seasons = remember(episodes) { EpisodeGuide.seasons(episodes) }
    val lastPlayedSeason = remember(progress, v.key) {
        progress.values.filter { it.kind == ProgressKey.EPISODE && it.sourceId == v.sourceId && it.itemId == v.seriesId }.maxByOrNull { it.updatedAt }?.season
    }
    var pickedSeason by remember(v.key) { mutableStateOf<Int?>(null) }
    val season = pickedSeason?.takeIf { it in seasons } ?: EpisodeGuide.defaultSeason(seasons, lastPlayedSeason)
    // Provider first; TMDB, then TheTVDB, fill a missing season overview, trailer, episode still or synopsis.
    var extras by remember(v.key, season) { mutableStateOf<SeasonExtras?>(null) }
    LaunchedEffect(v.key, season, info != null) { if (info != null && season != null) extras = app.extras.season(v, season) }
    var showTrailer by remember(v.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(v.key, info) { info?.let { showTrailer = it.trailer ?: app.extras.showTrailer(v) } }
    val inSeason = remember(episodes, season, extras) {
        episodes.filter { it.season == season }.sortedBy { it.episode }.map { e ->
            val meta = extras?.episodes?.get(e.episode)
            if (meta == null) e else e.copy(image = e.image ?: meta.still, plot = e.plot ?: meta.overview)
        }
    }
    val providerSeason = info?.seasons?.firstOrNull { it.number == season }
    val seasonOverview = providerSeason?.overview ?: extras?.overview
    val seasonTrailer = providerSeason?.trailer ?: extras?.trailer
    val next = remember(episodes, progress) {
        EpisodeGuide.nextUp(episodes, { progressOf(it)?.watched == true }, { ProgressRules.started(progressOf(it)) })
    }
    val seasonWatched = inSeason.isNotEmpty() && inSeason.all { progressOf(it)?.watched == true }
    val menus = LocalItemMenus.current
    fun mark(eps: List<XtreamParser.Episode>, watched: Boolean, what: String) {
        app.scope.launch {
            app.watched.episodes(v.sourceId, v.seriesId, eps.map { it.season to it.episode }, watched)
            toast(ctx, "$what ${if (watched) "marked as watched" else "marked as unwatched"}")
        }
    }
    fun episodeMenu(e: XtreamParser.Episode) {
        val watched = progressOf(e)?.watched == true
        val before = EpisodeGuide.upTo(episodes, e)
        menus.open = ItemMenu(
            "S${e.season} E${e.episode}  ${EpisodeGuide.title(e)}",
            buildList {
                add((if (watched) "Mark as unwatched" else "✓  Mark as watched") to { mark(listOf(e), !watched, "Episode ${e.episode}") })
                if (before.size > 1 && before.any { progressOf(it)?.watched != true }) {
                    add("✓  Mark watched up to here" to { mark(before, true, "${before.size} episodes") })
                }
                add("▶  Play" to { playEpisode(scope, ctx, profile, v, e) })
            },
        )
    }

    val all = remember(group.key, copies) { SeriesGroup(group.key, copies) }
    // New episodes count down as they are watched; opening the show only clears an old-style count.
    LaunchedEffect(copies) { copies.forEach { app.newEpisodes.seen(it.sourceId, it.seriesId) } }
    val favRows = rememberFavouriteShowRows()
    val favourite = remember(favRows, all) { ShowFavourites(favRows).has(all) }
    val cover = info?.cover ?: v.poster ?: group.poster
    val description = info?.plot ?: v.plot ?: group.plot
    val title = SeriesTitles.parse(v.name).title

    var sheet by remember(v.key) { mutableStateOf<XtreamParser.Episode?>(null) }
    var preview by remember(v.key, season) { mutableStateOf<XtreamParser.Episode?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(info != null) { if (info != null && !form.touch) { delay(60); runCatching { first.requestFocus() } } }

    val list: @Composable (Modifier) -> Unit = { mod ->
        LazyColumn(mod, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (form.touch) item("back") { TvButton("‹  Back", onBack) }
            item("title") {
                Row(verticalAlignment = Alignment.Top) {
                    if (phone) Box(Modifier.width(96.dp).aspectRatio(2f / 3f).clip(Tv.Shape).background(Tv.Raised)) {
                        if (cover != null) AsyncImage(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (phone) Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, fontSize = if (phone) 22.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            v.year?.toString(),
                            v.rating?.let { "★ $it" },
                            seasons.count { it > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                            SeriesTitles.languageName(v.lang ?: SeriesTitles.ORIGINAL).takeIf { copies.size > 1 },
                        )
                        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item("actions") {
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (next != null) item("play") {
                        val resume = ProgressRules.started(progressOf(next))
                        TvButton(
                            "▶  ${if (resume) "Resume" else "Play"} S${next.season} E${next.episode}",
                            { playEpisode(scope, ctx, profile, v, next) },
                            Modifier.focusRequester(first),
                        )
                    }
                    item("fav") {
                        TvButton(
                            if (favourite) "♥  In Favourites" else "♡  Add to Favourites",
                            { toggleShowFavourite(ctx, all, favourite, v) },
                            if (next == null) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                    if (season != null && inSeason.isNotEmpty()) item("season-watched") {
                        val label = EpisodeGuide.seasonLabel(season)
                        TvButton(
                            if (seasonWatched) "Mark $label unwatched" else "✓  Mark $label watched",
                            { mark(inSeason, !seasonWatched, label) },
                        )
                    }
                }
            }
            item("about") { ExpandableText(description ?: "No description", collapsedLines = if (phone) 4 else 3) }
            if (copies.size > 1) item("lang") {
                Column {
                    Text("Language", fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(bottom = 6.dp))
                    ChoiceChips(labels, copies.indexOf(v)) { i ->
                        val c = copies[i]
                        chosenKey = c.key
                        languages.remember(copies, c.lang ?: SeriesTitles.ORIGINAL)
                    }
                }
            }
            if (seasons.size > 1) item("seasons") { ChoiceChips(seasons.map(EpisodeGuide::seasonLabel), seasons.indexOf(season)) { pickedSeason = seasons[it] } }
            // Episodes with their own trailers get a button each; otherwise the season (or show) trailer goes here.
            val trailer = if (inSeason.any { it.trailer != null }) null else seasonTrailer ?: showTrailer
            if (season != null && (seasonOverview != null || trailer != null)) item("season-about") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (seasonOverview != null) ExpandableText(seasonOverview, collapsedLines = 3)
                    if (trailer != null) TrailerButton(trailer, label = if (trailer == seasonTrailer) "${EpisodeGuide.seasonLabel(season)} trailer" else "Trailer")
                }
            }
            when {
                info == null -> item("loading") { Text("Loading episodes…", color = Tv.Muted) }
                inSeason.isEmpty() -> item("none") { Text("No episodes found.", color = Tv.Muted) }
                else -> {
                    if (!form.touch) item("hint") { Text("Hold OK (or press Menu) on an episode to mark it watched.", fontSize = 13.sp, color = Tv.Muted) }
                    // The index keeps keys unique when a provider lists an episode twice.
                    itemsIndexed(inSeason, key = { i, e -> "${v.key}/$i/${e.id}" }) { _, e ->
                        EpisodeRow(
                            e, cover, progressOf(e),
                            onFocus = { preview = e },
                            onClick = { if (form.touch) sheet = e else playEpisode(scope, ctx, profile, v, e) },
                            onLongClick = { episodeMenu(e) },
                        )
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (form) {
            FormFactor.Phone -> list(Modifier.fillMaxSize())
            FormFactor.Tablet -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(Tv.Shape).background(Tv.Raised)) {
                    if (cover != null) AsyncImage(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                list(Modifier.weight(1f))
            }
            FormFactor.Tv -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                EpisodePreview(preview, cover, preview?.let(::progressOf), Modifier.width(300.dp))
                list(Modifier.weight(1f))
            }
        }
        sheet?.let { e ->
            val watched = progressOf(e)?.watched == true
            EpisodeSheet(
                e, cover, progressOf(e), phone,
                onPlay = { sheet = null; playEpisode(scope, ctx, profile, v, e) },
                onWatched = { mark(listOf(e), !watched, "Episode ${e.episode}") },
                onClose = { sheet = null },
            )
        }
    }
}

private fun playEpisode(scope: CoroutineScope, ctx: android.content.Context, profile: Profile, v: SeriesEntity, e: XtreamParser.Episode) {
    scope.launch {
        Streams(ctx.app.db, profile).episode(v.sourceId, e)?.let {
            play(ctx, it, "S${e.season} E${e.episode} ${EpisodeGuide.title(e)}", PlayItem.episode(v.sourceId, v.seriesId, v.name, e.season, e.episode))
        }
    }
}

/** A row of selectable chips (languages, seasons); on TV a focusable row that remembers its place. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChoiceChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val minH = if (LocalFormFactor.current.touch) 48.dp else 0.dp
    LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 40.dp)) {
        itemsIndexed(labels) { i, label ->
            FocusBox(onClick = { onSelect(i) }, selected = i == selected, color = Tv.Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.heightIn(min = minH), contentAlignment = Alignment.CenterStart) { focused ->
                Text(
                    label, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 15.sp, maxLines = 1,
                    color = if (i == selected && !focused) Tv.Accent else Tv.Text,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Text that shows [collapsedLines] and, when there is more, a More/Less toggle: tap the text or
 * the toggle on touch, focus the toggle and press OK on TV.
 */
@Composable
fun ExpandableText(text: String, collapsedLines: Int = 3, fontSize: TextUnit = 14.sp, color: Color = Tv.Muted) {
    val touch = LocalFormFactor.current.touch
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val toggle = { expanded = !expanded }
    Column {
        Text(
            text, fontSize = fontSize, color = color, lineHeight = fontSize * 1.4f,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = if (touch && (overflows || expanded)) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = toggle) else Modifier,
        )
        if (overflows || expanded) {
            FocusBox(onClick = toggle, color = Color.Transparent, modifier = Modifier.heightIn(min = if (touch) 48.dp else 0.dp), contentAlignment = Alignment.CenterStart) { focused ->
                Text(
                    if (expanded) "Less" else "More", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (focused) Tv.Text else Tv.Accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** An episode's still, or the show's cover when the provider has none. */
@Composable
private fun EpisodeStill(e: XtreamParser.Episode, cover: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(16f / 9f).clip(Tv.Shape).background(Tv.Raised), contentAlignment = Alignment.Center) {
        Text("S${e.season} E${e.episode}", fontSize = 13.sp, color = Tv.Muted)
        val art = e.image ?: cover
        if (art != null) AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun meta(e: XtreamParser.Episode, long: Boolean): String = listOfNotNull(
    EpisodeGuide.runtime(e.durationSecs),
    e.airDate?.let { if (long) "Aired $it" else it },
    e.rating?.takeIf { long }?.let { "★ $it" },
).joinToString("  ·  ")

@Composable
private fun EpisodeRow(e: XtreamParser.Episode, cover: String?, p: ProgressEntity?, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val form = LocalFormFactor.current
    val stillWidth = when (form) { FormFactor.Phone -> 128.dp; FormFactor.Tablet -> 168.dp; FormFactor.Tv -> 128.dp }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusBox(onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { focused ->
            Row(Modifier.padding(10.dp)) {
                EpisodeStill(e, cover, Modifier.width(stillWidth))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${e.episode}. ${EpisodeGuide.title(e)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val m = meta(e, long = false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.isNotEmpty()) Text(m, fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Muted, maxLines = 1)
                        if (p?.watched == true) Text((if (m.isNotEmpty()) "  ·  " else "") + "✓ Watched", fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Good, maxLines = 1)
                    }
                    Text(
                        e.plot ?: "No description", fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Muted,
                        maxLines = if (form.touch) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                    val bar = ProgressRules.bar(p)
                    if (bar > 0f) ProgressLine(bar, Modifier.padding(top = 8.dp).widthIn(max = 260.dp), fill = if (focused) Tv.Text else Tv.Accent)
                }
            }
        }
        e.trailer?.let { TrailerButton(it, compact = form == FormFactor.Phone) }
    }
}

/** TV: the focused episode's still and synopsis beside the list (the show's cover until one is focused). */
@Composable
private fun EpisodePreview(e: XtreamParser.Episode?, cover: String?, p: ProgressEntity?, modifier: Modifier) {
    Column(modifier) {
        if (e == null) {
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(Tv.Shape).background(Tv.Raised)) {
                if (cover != null) AsyncImage(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            return@Column
        }
        EpisodeStill(e, cover, Modifier.fillMaxWidth())
        Text("Season ${e.season}  ·  Episode ${e.episode}", fontSize = 13.sp, color = Tv.Accent, modifier = Modifier.padding(top = 12.dp))
        Text(EpisodeGuide.title(e), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        val m = meta(e, long = true)
        if (m.isNotEmpty()) Text(m, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(top = 4.dp))
        if (p?.watched == true) Text("✓ Watched", fontSize = 13.sp, color = Tv.Good, modifier = Modifier.padding(top = 4.dp))
        Text(e.plot ?: "No description", fontSize = 14.sp, color = Tv.Muted, lineHeight = 20.sp, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Touch: the episode's large still, details and Play/Resume, over the page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeSheet(e: XtreamParser.Episode, cover: String?, p: ProgressEntity?, phone: Boolean, onPlay: () -> Unit, onWatched: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val none = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(none, indication = null, onClick = onClose),
        contentAlignment = if (phone) Alignment.BottomCenter else Alignment.Center,
    ) {
        val shape = if (phone) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(16.dp)
        Column(
            (if (phone) Modifier.fillMaxWidth() else Modifier.width(560.dp))
                .heightIn(max = maxHeight * 0.92f)
                .clip(shape)
                .background(Tv.Panel)
                .clickable(remember { MutableInteractionSource() }, indication = null) {} // taps inside don't close it
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tv.Raised)) {
                val art = e.image ?: cover
                if (art != null) AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.padding(18.dp)) {
                Text("Season ${e.season}  ·  Episode ${e.episode}", fontSize = 13.sp, color = Tv.Accent)
                Text(EpisodeGuide.title(e), fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                val m = meta(e, long = true)
                if (m.isNotEmpty()) Text(m, fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.padding(top = 4.dp))
                if (p?.watched == true) Text("✓ Watched", fontSize = 14.sp, color = Tv.Good, modifier = Modifier.padding(top = 4.dp))
                val bar = ProgressRules.bar(p)
                if (bar > 0f) ProgressLine(bar, Modifier.padding(top = 8.dp).widthIn(max = 260.dp))
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(if (ProgressRules.started(p)) "▶  Resume" else "▶  Play", onPlay)
                    TvButton(if (p?.watched == true) "Mark unwatched" else "✓  Mark watched", onWatched)
                    e.trailer?.let { TrailerButton(it) }
                    TvButton("Close", onClose)
                }
                Spacer(Modifier.height(14.dp))
                Text(e.plot ?: "No description", fontSize = 15.sp, lineHeight = 22.sp, color = if (e.plot == null) Tv.Muted else Tv.Text)
            }
        }
    }
}
