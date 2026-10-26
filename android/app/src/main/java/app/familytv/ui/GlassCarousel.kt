package app.familytv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Text
import app.familytv.app
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a carousel slide shows before the next, and the cross-fade between them. */
internal const val SLIDE_SHOW_MS = 8_000L
internal const val SLIDE_FADE_MS = 700
/**
 * The carousel's slot in mockup pixels (720 tall): under the top bar it reaches about 70% down, so
 * with the art's fade the carousel fills the top three quarters of the screen.
 */
internal const val CAROUSEL_HEIGHT = 340

/**
 * The Home carousel's text and buttons (the backdrop itself is [HomeBackdrop], drawn by the TV home
 * behind the top bar): the title logo, one detail line, Play and More info, and a dot per slide;
 * [entry] lands focus on Play.
 * It moves on every [SLIDE_SHOW_MS] while it is on screen and the app is in front (not under the
 * player, a dialog or the screensaver); Left on Play and Right on the last button step through it
 * and restart the wait. Always on the dark look: the scrim under it stays dark in the light look.
 */
@Composable
internal fun HomeCarousel(slides: List<Slide>, marks: VodMarks, modifier: Modifier = Modifier, entry: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shell = LocalGlassShell.current
    val nav = LocalSeriesNav.current
    val scope = rememberCoroutineScope()
    // Back on Home, the slide that was showing comes back as it was, with no fade from another.
    var index by remember(slides) { mutableIntStateOf(Featured.lastIndex.takeIf { it in slides.indices } ?: 0) }
    LaunchedEffect(index) { Featured.lastIndex = index }
    val slide = slides[index.coerceIn(slides.indices)]
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val paused = resumed != Lifecycle.State.RESUMED || shell?.saver?.value == true
    var notYet by remember(slide.key) { mutableStateOf(false) }
    // Which button has focus (0 Play, 1 More info): Left and Right step through the slides from the outer ones.
    var focusedButton by remember { mutableIntStateOf(0) }
    val step = { by: Int -> index = (index + by + slides.size) % slides.size }

    DisposableEffect(shell) { onDispose { shell?.backdrop?.value = null } }
    LaunchedEffect(slide) { shell?.backdrop?.value = slide }
    LaunchedEffect(index, paused, slides) {
        if (paused || slides.size < 2) return@LaunchedEffect
        // The next backdrop decodes while this one shows, at the size it is drawn.
        slides[(index + 1) % slides.size].backdrop?.let { ctx.imageLoader.enqueue(backdropRequest(ctx, it)) }
        delay(SLIDE_SHOW_MS)
        step(1)
    }

    val play: () -> Unit = {
        when (val t = slide.target) {
            is Slide.Target.Movie -> scope.launch { playMovie(ctx, marks.profile, t.g, marks.movieKeys, marks.progress) }
            is Slide.Target.Show -> nav?.open = t.g
            is Slide.Target.Event ->
                if (System.currentTimeMillis() < t.start - EVENT_EARLY_MS) notYet = true
                else scope.launch { playLive(ctx, t.channel.sourceId, t.channel.categoryId, eventIndex(ctx.app, t.channel), t.channel.streamId) }
        }
    }
    val more: (() -> Unit)? = when (val t = slide.target) {
        is Slide.Target.Movie -> { { nav?.movie = t.g to marks.movieKeys } }
        is Slide.Target.Show -> { { nav?.open = t.g } }
        is Slide.Target.Event -> null
    }
    LaunchedEffect(notYet) { if (notYet) { delay(3_000); notYet = false } }

    OnDarkArt {
        // The title and buttons sit low in the art, the rows just under its fade.
        Column(modifier.fillMaxWidth().padding(start = FOCUS_ROOM, bottom = 14.gx), verticalArrangement = Arrangement.spacedBy(12.gx, Alignment.Bottom)) {
            Box(Modifier.heightIn(min = 96.gx), contentAlignment = Alignment.BottomStart) {
                if (slide.logo != null) {
                    AsyncImage(slide.logo, contentDescription = slide.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(96.gx).widthIn(max = 440.gx))
                } else {
                    Text(slide.title, fontSize = 40.gsp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 620.gx))
                }
            }
            val event = slide.target as? Slide.Target.Event
            Text(
                if (notYet && event != null) "Starts ${Featured.startsAt(event.start)}" else slide.detail,
                fontSize = 17.gsp, color = Glass.Soft, maxLines = 1,
            )
            Row(
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || slides.size < 2) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> if (focusedButton == 0) { step(-1); true } else false
                        Key.DirectionRight -> if (focusedButton == (if (more != null) 1 else 0)) { step(1); true } else false
                        else -> false
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(12.gx),
            ) {
                GlassPill("Play", play, entry.onFocusChanged { if (it.isFocused) focusedButton = 0 }, fill = Glass.Raised, border = true, fontSize = 17.gsp, padH = 26.gx, padV = 10.gx)
                more?.let { GlassPill("More info", it, Modifier.onFocusChanged { f -> if (f.isFocused) focusedButton = 1 }, fill = Glass.Raised, border = true, fontSize = 17.gsp, padH = 26.gx, padV = 10.gx) }
            }
            if (slides.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.gx), modifier = Modifier.padding(top = 2.gx)) {
                slides.indices.forEach { i ->
                    Box(Modifier.size(if (i == index) 9.gx else 7.gx).background(if (i == index) Color.White else Color(0x66FFFFFF), CircleShape))
                }
            }
        }
    }
}

/** An event can be opened this long before it starts: its channel is usually showing the build-up. */
private const val EVENT_EARLY_MS = 15 * 60_000L

/**
 * A backdrop decoded off the main thread at the screen's size, in 16-bit colour (half the memory
 * and upload of 32-bit; photos show no difference), so the preload and the drawn image share one
 * cached bitmap.
 */
private fun backdropRequest(ctx: android.content.Context, url: String): ImageRequest {
    val m = ctx.resources.displayMetrics
    return ImageRequest.Builder(ctx).data(url).size(m.widthPixels, m.heightPixels).allowRgb565(true).build()
}

/**
 * The carousel's backdrop, full-bleed behind the TV home's top bar and page: the slide's TMDB art
 * (or an event's art over a dark sports backdrop), with a dark scrim behind the carousel's text and
 * a fade into the page's background below it, while [show]. Only this reads [slide], so a new slide
 * redraws the backdrop and nothing else of the home. The new art fades in over the old in
 * [SLIDE_FADE_MS] with its alpha applied to its own pixels (no offscreen copy of the screen), and
 * the scrims sit in one cached layer; a plain cut when the system asks for less motion. No zoom or
 * pan, so the onn keeps up.
 */
@Composable
internal fun BoxScope.HomeBackdrop(slide: State<Slide?>, show: Boolean) {
    val target = slide.value.takeIf { show }
    val still = LocalReducedMotion.current
    val shown by animateFloatAsState(if (target != null) 1f else 0f, tween(if (still) 0 else 400), label = "backdrop")
    // The slide in front, and the one it is fading in over (none once the fade ends).
    var front by remember { mutableStateOf<Slide?>(null) }
    var behind by remember { mutableStateOf<Slide?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == null || target === front) return@LaunchedEffect
        behind = front
        front = target
        if (behind != null && !still) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(SLIDE_FADE_MS))
        }
        behind = null
    }
    if (shown == 0f || front == null) return
    val p = LocalGlassPalette.current
    Box(Modifier.matchParentSize().graphicsLayer { alpha = shown; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
        listOfNotNull(behind, front).forEach { s ->
            androidx.compose.runtime.key(s.key) {
                BackdropArt(s, if (s === front && behind != null) Modifier.graphicsLayer { alpha = fade.value; compositingStrategy = CompositingStrategy.ModulateAlpha } else Modifier)
            }
        }
        // Dark behind the text on the left whatever the look; below the carousel, the page's own colour.
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
                val dark = Color(0xE6060A14)
                val across = Brush.horizontalGradient(0f to dark, 0.45f to Color(0x99060A14), 0.75f to Color.Transparent)
                // The top bar's light text sits on this shade, whatever the art.
                // The art fills the top three quarters of the screen (Arshad, Oct 26), fading into the page by 78%.
                val down = Brush.verticalGradient(0f to Color(0xCC060A14), 0.12f to Color(0x8C060A14), 0.24f to Color.Transparent, 0.56f to Color.Transparent, 0.68f to p.middle.copy(alpha = 0.75f), 0.78f to p.middle)
                onDrawBehind { drawRect(across); drawRect(down) }
            },
        )
    }
}

@Composable
private fun BackdropArt(s: Slide, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize()) {
        val url = s.backdrop
        if (url != null) {
            AsyncImage(backdropRequest(ctx, url), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF07131F), Color(0xFF0B2A22), Color(0xFF060A14)))))
            s.art?.let {
                AsyncImage(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.TopEnd).padding(top = 90.gx, end = 90.gx).size(360.gx, 240.gx))
            }
        }
    }
}
