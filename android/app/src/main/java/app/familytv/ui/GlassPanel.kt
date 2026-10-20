package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.FamilyTvApp
import app.familytv.app
import app.familytv.core.Http
import app.familytv.user.GlassPrefs
import app.familytv.user.WorldClock
import app.familytv.weather.Forecast
import app.familytv.weather.OpenMeteo
import app.familytv.weather.PlaceHit
import app.familytv.weather.WeatherParse
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Open-Meteo is asked at most this often; a failed first try is retried sooner. */
private const val REFRESH_MS = 30 * 60_000L
private const val RETRY_MS = 5 * 60_000L

/**
 * The weather for the panel and for Appearance's Auto: the last answer at once (saved on the TV),
 * then Open-Meteo at most every 30 minutes while the home is open.
 */
internal class GlassWeather(private val app: FamilyTvApp) {
    var forecast by mutableStateOf<Forecast?>(null)
        private set
    var updatedAt by mutableLongStateOf(0L)
        private set
    var failed by mutableStateOf(false)
        private set
    private var tried = 0L
    private val api = OpenMeteo(Http.client)

    /** The saved answer for the current city (none after the city changes). */
    fun loadCached() {
        val (at, body) = app.glassPrefs.cachedWeather() ?: (0L to "")
        forecast = WeatherParse.forecast(body)
        updatedAt = if (forecast != null) at else 0L
        tried = 0L
        failed = false
    }

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (forecast != null && now - updatedAt < REFRESH_MS) return
        if (tried > 0 && now - tried < if (forecast == null) RETRY_MS else REFRESH_MS) return
        tried = now
        val f = runCatching { api.forecastBody(app.glassPrefs.place.value) }.getOrNull()?.let { body -> WeatherParse.forecast(body)?.let { it to body } }
        failed = f == null
        val (forecast, body) = f ?: return
        app.glassPrefs.saveWeather(now, body)
        this.forecast = forecast
        updatedAt = now
    }
}

/** The TV home's [GlassWeather], following the chosen city. */
@Composable
internal fun rememberGlassWeather(): GlassWeather {
    val app = LocalContext.current.app
    val place by app.glassPrefs.place.collectAsState()
    val weather = remember { GlassWeather(app) }
    LaunchedEffect(place) {
        weather.loadCached()
        while (true) { weather.refresh(); delay(60_000) }
    }
    return weather
}

/** "^ Weather and clocks" at the foot of the home screens; focusing or selecting it opens the panel. */
@Composable
internal fun WeatherHint(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onOpen, onFocus = onOpen, color = Color.Transparent, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.gx, vertical = 4.gx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.gx)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Glass.Faint, modifier = Modifier.size(16.gx))
            Text("Weather and clocks", fontSize = 13.gsp, color = Glass.Faint, maxLines = 1)
        }
    }
}

private enum class PanelPage { Main, City, Clocks }

/**
 * The slide-up glass panel: the weather (always) and, when switched on, up to four analog world
 * clocks. Back closes it, as does Up from its top row; focus stays inside while it's open.
 */
@Composable
internal fun WeatherPanel(weather: GlassWeather, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(PanelPage.Main) }
    val open = remember { mutableStateOf(true) }
    val first = remember(page) { FocusRequester() }
    val close = { open.value = false; onClose() }
    BackHandler { if (page == PanelPage.Main) close() else page = PanelPage.Main }
    LaunchedEffect(page) { delay(60); runCatching { first.requestFocus() } }
    val shape = RoundedCornerShape(topStart = 32.gx, topEnd = 32.gx)
    val p = LocalGlassPalette.current
    Column(
        modifier
            .padding(horizontal = 32.gx)
            .fillMaxWidth()
            .height(if (page == PanelPage.Main) 470.gx else 600.gx)
            // Solid: see-through, busy pages showed through the weather (TV test, Oct 20).
            .background(p.middle, shape)
            .border(1.dp, Glass.Line, shape)
            .focusProperties {
                onExit = {
                    if (open.value) {
                        if (requestedFocusDirection == FocusDirection.Up) { if (page == PanelPage.Main) close() else page = PanelPage.Main }
                        cancelFocusChange()
                    }
                }
            }
            .focusGroup()
            .padding(start = 32.gx, end = 32.gx, top = 14.gx, bottom = 28.gx),
        verticalArrangement = Arrangement.spacedBy(18.gx),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(64.gx, 5.gx).background(Glass.Faint, RoundedCornerShape(50)))
        when (page) {
            PanelPage.Main -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.gx)) {
                WeatherCard(weather, Modifier.width(430.gx).fillMaxHeight(), first) { page = PanelPage.City }
                ClocksCard(Modifier.weight(1f).fillMaxHeight()) { page = PanelPage.Clocks }
            }
            PanelPage.City -> CityPage(first) { page = PanelPage.Main }
            PanelPage.Clocks -> ClockChooser(first)
        }
    }
}

@Composable
private fun WeatherCard(weather: GlassWeather, modifier: Modifier, first: FocusRequester, onChangeCity: () -> Unit) {
    val place by LocalContext.current.app.glassPrefs.place.collectAsState()
    val f = weather.forecast
    val shape = RoundedCornerShape(24.gx)
    Column(
        modifier.background(Brush.linearGradient(listOf(Color(0x594F7CFF), Color(0x144F7CFF))), shape).border(1.dp, Glass.LineSoft, shape).padding(horizontal = 24.gx, vertical = 20.gx),
        verticalArrangement = Arrangement.spacedBy(14.gx),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place.name, fontSize = 18.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f != null) Text("Updated ${time(weather.updatedAt)}", fontSize = 13.gsp, color = Glass.Dim, maxLines = 1)
            }
            GlassPill("Change city", onChangeCity, Modifier.focusRequester(first), fill = Glass.Raised, border = true, fontSize = 14.gsp, padH = 14.gx, padV = 6.gx)
        }
        if (f == null) {
            Text(if (weather.failed) "Couldn't reach the weather service. It tries again in a few minutes." else "Loading the weather…", fontSize = 15.gsp, color = Glass.Dim)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.gx)) {
            Text(WeatherParse.icon(f.code, f.isDay), fontSize = 54.gsp)
            Text("${f.temp}°", fontSize = 68.gsp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, maxLines = 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.gx)) {
                Text(WeatherParse.condition(f.code), fontSize = 15.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (f.high != null && f.low != null) Text("H ${f.high}° · L ${f.low}°", fontSize = 15.gsp, color = Glass.Dim, maxLines = 1)
                nextSun(f, System.currentTimeMillis())?.let { (label, at) -> Text("$label ${cityTime(at, f.offsetSeconds)}", fontSize = 15.gsp, color = Glass.Dim, maxLines = 1) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.LineSoft))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            f.days.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.gx)) {
                    Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 14.gsp, color = Glass.Dim)
                    Text(WeatherParse.icon(d.code), fontSize = 20.gsp)
                    Text("${d.high}°", fontSize = 14.gsp, fontWeight = FontWeight.SemiBold)
                    Text("${d.low}°", fontSize = 14.gsp, color = Glass.Faint)
                }
            }
        }
    }
}

/** "Sunset" and today's sunset while the sun is up, else "Sunrise" and the next one. */
internal fun nextSun(f: Forecast, now: Long): Pair<String, Long>? {
    f.sunrises.zip(f.sunsets).firstOrNull { (rise, set) -> now in rise until set }?.let { return "Sunset" to it.second }
    return f.sunrises.firstOrNull { it > now }?.let { "Sunrise" to it }
}

/** [at] as the weather city's own clock reads it. */
private fun cityTime(at: Long, offsetSeconds: Int): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = SimpleTimeZone(offsetSeconds * 1000, "city") }.format(Date(at))

@Composable
private fun ClocksCard(modifier: Modifier, onChoose: () -> Unit) {
    val prefs = LocalContext.current.app.glassPrefs
    val on by prefs.clocksOn.collectAsState()
    val clocks by prefs.clocks.collectAsState()
    val shape = RoundedCornerShape(24.gx)
    Column(modifier.background(Glass.Fill, shape).border(1.dp, Glass.LineSoft, shape).padding(horizontal = 24.gx, vertical = 20.gx), verticalArrangement = Arrangement.spacedBy(16.gx)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.gx)) {
            Text("World clocks", fontSize = 18.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            GlassToggle("Show world clocks", on, { prefs.setClocksOn(!on) })
            GlassPill("Choose countries", onChoose, selected = true, fontSize = 14.gsp, padH = 16.gx, padV = 8.gx)
        }
        if (on && clocks.isNotEmpty()) {
            val now = rememberMinute()
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                clocks.forEach { c -> ClockFace(c, now) }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (on) "Choose up to 4 countries to see their time." else "Switch on Show world clocks to see the time in up to 4 countries.", fontSize = 15.gsp, color = Glass.Dim)
            }
        }
    }
}

/** The time now, ticking over on each minute. */
@Composable
internal fun rememberMinute(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000 - now % 60_000); now = System.currentTimeMillis() } }
    return now
}

/** One analog clock: light by day (6 AM to 6 PM there), dark by night, with its city and the hour difference. */
@Composable
private fun ClockFace(c: WorldClock, now: Long) {
    val zone = runCatching { ZoneId.of(c.zone) }.getOrNull() ?: return
    val t = Instant.ofEpochMilli(now).atZone(zone)
    val day = t.hour in 6..17
    val face = if (day) Color(0xFFF4F6FB) else Color(0x8C0A0E18)
    val ink = if (day) Color(0xFF0B0F18) else Color(0xFFF5F7FA)
    val minutes = t.hour % 12 * 60 + t.minute
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.gx)) {
        Canvas(Modifier.size(124.gx)) {
            val u = size.width / 100f
            val c0 = Offset(50f * u, 50f * u)
            fun at(deg: Float, len: Float) = Offset(50f * u + len * u * sin(Math.toRadians(deg.toDouble())).toFloat(), 50f * u - len * u * cos(Math.toRadians(deg.toDouble())).toFloat())
            drawCircle(face, 47f * u, c0)
            drawCircle(Color(0x59FFFFFF), 47f * u, c0, style = Stroke(1.5f * u))
            for (h in 0 until 12) drawLine(ink, at(h * 30f, 36f), at(h * 30f, 43f), 2.2f * u, StrokeCap.Round)
            drawLine(ink, c0, at(minutes / 2f, 24f), 4.5f * u, StrokeCap.Round)
            drawLine(ink, c0, at(t.minute * 6f, 35f), 3f * u, StrokeCap.Round)
            drawCircle(Color(0xFFFF9F43), 3.2f * u, c0)
        }
        Text(c.city, fontSize = 16.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("${c.country} · ${hourDifference(zone, ZoneId.systemDefault(), Instant.ofEpochMilli(now))}", fontSize = 13.gsp, color = Glass.Dim, maxLines = 1)
    }
}

/** How far [zone] is ahead of [local]: "+9 h", "−4 h", "+9½ h", "local" for this TV's own zone. */
internal fun hourDifference(zone: ZoneId, local: ZoneId, at: Instant): String {
    if (zone.normalized() == local.normalized()) return "local"
    val d = zone.rules.getOffset(at).totalSeconds - local.rules.getOffset(at).totalSeconds
    if (d == 0) return "same time"
    val m = abs(d) / 60
    val part = when (m % 60) { 15 -> "¼"; 30 -> "½"; 45 -> "¾"; else -> "" }
    return "${if (d > 0) "+" else "−"}${m / 60}$part h"
}

/** A labelled on/off switch in a pill: green track and white knob when on. */
@Composable
private fun GlassToggle(label: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onToggle, color = Color.Transparent, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.gx, vertical = 6.gx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.gx)) {
            Text(label, fontSize = 15.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Box(Modifier.size(48.gx, 28.gx).background(if (on) Color(0xFF3CCF6E) else Glass.Raised, RoundedCornerShape(50)).border(1.dp, Glass.LineSoft, RoundedCornerShape(50)).padding(3.gx)) {
                Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).size(22.gx).background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}

/** "Change city": Open-Meteo's place search; only the chosen place's coordinates go into the forecast. */
@Composable
private fun ColumnScope.CityPage(first: FocusRequester, onDone: () -> Unit) {
    val prefs = LocalContext.current.app.glassPrefs
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PlaceHit>?>(emptyList()) }
    val api = remember { OpenMeteo(Http.client) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(400)
        hits = runCatching { api.search(q) }.getOrNull()
    }
    Text("Change city", fontSize = 26.gsp, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.gx)) {
        GlassSearchBox(query, { query = it }, "Search a city", Modifier.focusRequester(first))
        GlassPill("Toronto", { prefs.setPlace(GlassPrefs.TORONTO); onDone() }, fill = Glass.Raised, border = true, fontSize = 14.gsp)
    }
    val list = hits
    when {
        list == null -> Text("Couldn't reach the place search. Check the internet connection.", fontSize = 15.gsp, color = Glass.Dim)
        list.isEmpty() && query.trim().length >= 2 -> Text("No places found.", fontSize = 15.gsp, color = Glass.Dim)
        else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.gx)) {
            items(list) { h ->
                GlassRowItem(listOfNotNull(h.place.name, h.region, h.place.country.takeIf { it.isNotEmpty() }).distinct().joinToString(", "), false, { prefs.setPlace(h.place); onDone() })
            }
        }
    }
}

/** A country for the world clocks, and its cities when it spans several time zones. */
internal class ClockCountry(val name: String, val cities: List<Pair<String, String>>) {
    constructor(name: String, city: String, zone: String) : this(name, listOf(city to zone))
}

/** The chooser's countries: the family's own first, as in the mockup. */
internal val CLOCK_COUNTRIES = listOf(
    ClockCountry(
        "Canada",
        listOf("Toronto" to "America/Toronto", "Vancouver" to "America/Vancouver", "Calgary" to "America/Edmonton", "Winnipeg" to "America/Winnipeg", "Halifax" to "America/Halifax", "St. John's" to "America/St_Johns"),
    ),
    ClockCountry("Pakistan", "Karachi", "Asia/Karachi"),
    ClockCountry("United Arab Emirates", "Dubai", "Asia/Dubai"),
    ClockCountry("United Kingdom", "London", "Europe/London"),
    ClockCountry("India", "New Delhi", "Asia/Kolkata"),
    ClockCountry("Iran", "Tehran", "Asia/Tehran"),
    ClockCountry("Philippines", "Manila", "Asia/Manila"),
    ClockCountry("Saudi Arabia", "Riyadh", "Asia/Riyadh"),
    ClockCountry(
        "United States",
        listOf("New York" to "America/New_York", "Chicago" to "America/Chicago", "Denver" to "America/Denver", "Los Angeles" to "America/Los_Angeles", "Anchorage" to "America/Anchorage", "Honolulu" to "Pacific/Honolulu"),
    ),
    ClockCountry("Bangladesh", "Dhaka", "Asia/Dhaka"),
    ClockCountry("Australia", listOf("Sydney" to "Australia/Sydney", "Brisbane" to "Australia/Brisbane", "Adelaide" to "Australia/Adelaide", "Perth" to "Australia/Perth")),
    ClockCountry("Afghanistan", "Kabul", "Asia/Kabul"),
    ClockCountry("Qatar", "Doha", "Asia/Qatar"),
    ClockCountry("Turkey", "Istanbul", "Europe/Istanbul"),
    ClockCountry("Egypt", "Cairo", "Africa/Cairo"),
    ClockCountry("France", "Paris", "Europe/Paris"),
    ClockCountry("Germany", "Berlin", "Europe/Berlin"),
    ClockCountry("Spain", "Madrid", "Europe/Madrid"),
    ClockCountry("Italy", "Rome", "Europe/Rome"),
    ClockCountry("Mexico", listOf("Mexico City" to "America/Mexico_City", "Cancún" to "America/Cancun", "Tijuana" to "America/Tijuana")),
    ClockCountry("Jamaica", "Kingston", "America/Jamaica"),
    ClockCountry("China", "Beijing", "Asia/Shanghai"),
    ClockCountry("Japan", "Tokyo", "Asia/Tokyo"),
    ClockCountry("Nigeria", "Lagos", "Africa/Lagos"),
)

/** "Choose countries": up to four, a city asked for where a country has several time zones. Saved on this TV. */
@Composable
private fun ColumnScope.ClockChooser(first: FocusRequester) {
    val prefs = LocalContext.current.app.glassPrefs
    val on by prefs.clocksOn.collectAsState()
    val clocks by prefs.clocks.collectAsState()
    var asking by remember { mutableStateOf<ClockCountry?>(null) }
    var full by remember { mutableStateOf(false) }
    val cityFocus = remember { FocusRequester() }
    BackHandler(enabled = asking != null) { asking = null }
    // Into the city list when a country asks which city, and back to the switch after.
    LaunchedEffect(asking) { delay(60); runCatching { (if (asking != null) cityFocus else first).requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.gx)) {
            Text(asking?.let { "Which city in ${it.name}?" } ?: "World clocks", fontSize = 26.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                if (full) "That's 4 already. Untick one first." else "Pick up to ${GlassPrefs.MAX_CLOCKS} countries. The weather always shows.",
                fontSize = 15.gsp, color = Glass.Dim, maxLines = 1,
            )
        }
        GlassToggle("Show world clocks", on, { prefs.setClocksOn(!on) }, Modifier.focusRequester(first))
    }
    val add = { c: WorldClock -> prefs.setClocks(clocks + c); if (!on) prefs.setClocksOn(true); asking = null }
    val ask = asking
    if (ask != null) {
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), contentPadding = PaddingValues(4.gx), horizontalArrangement = Arrangement.spacedBy(12.gx), verticalArrangement = Arrangement.spacedBy(12.gx)) {
            itemsIndexed(ask.cities) { i, (city, zone) ->
                ChooserTile(ask.name, city, false, if (i == 0) Modifier.focusRequester(cityFocus) else Modifier) {
                    add(WorldClock(city, ask.name, zone))
                }
            }
        }
    } else {
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), contentPadding = PaddingValues(4.gx), horizontalArrangement = Arrangement.spacedBy(12.gx), verticalArrangement = Arrangement.spacedBy(12.gx)) {
            itemsIndexed(CLOCK_COUNTRIES, key = { _, c -> c.name }) { _, c ->
                val chosen = clocks.firstOrNull { it.country == c.name }
                val city = chosen?.city ?: if (c.cities.size > 1) "${c.cities.size} time zones" else c.cities.first().first
                ChooserTile(c.name, city, chosen != null, Modifier) {
                    full = false
                    when {
                        chosen != null -> prefs.setClocks(clocks - chosen)
                        clocks.size >= GlassPrefs.MAX_CLOCKS -> full = true
                        c.cities.size > 1 -> asking = c
                        else -> add(WorldClock(c.cities.first().first, c.name, c.cities.first().second))
                    }
                }
            }
        }
    }
    Text("Countries with several time zones ask which city. Saved on this TV.", fontSize = 14.gsp, color = Glass.Faint)
}

/** One country (or city) in the chooser: a tick box, its name and the city it shows. */
@Composable
private fun ChooserTile(title: String, detail: String, ticked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Glass.Fill, shape = RoundedCornerShape(16.gx), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.gx, vertical = 12.gx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.gx)) {
            Box(Modifier.size(26.gx).background(if (ticked) Glass.Selected else Glass.Raised, RoundedCornerShape(8.gx)), contentAlignment = Alignment.Center) {
                if (ticked) Text("✓", fontSize = 16.gsp, fontWeight = FontWeight.Bold, color = Glass.OnSelected)
            }
            Column {
                Text(title, fontSize = 16.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 13.gsp, color = Glass.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
