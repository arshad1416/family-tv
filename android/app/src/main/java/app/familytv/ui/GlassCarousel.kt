package app.familytv.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.app
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a carousel slide shows before the next, and the cross-fade between them. */
internal const val SLIDE_SHOW_MS = 8_000L
internal const val SLIDE_FADE_MS = 700
/**
 * The carousel's slot in mockup pixels (720 tall), at most and at least: Home sizes it so the first
 * row (Continue watching) shows whole under it, over the art's lower part (Arshad, Oct 26); the
 * art itself still fills about the top three quarters of the screen.
 */
internal const val CAROUSEL_HEIGHT = 340
internal const val CAROUSEL_MIN = 140
/** Below this the carousel's title and buttons draw smaller ([HomeCarousel]'s compact), so the first row still shows whole. */
internal const val CAROUSEL_COMPACT = 190
/** The space between Home's rows, and so between the carousel and the first row. */
internal const val ROW_GAP = 22

/**
 * The Home carousel's text and buttons (the backdrop itself is [HomeBackdrop], drawn by the TV home
 * behind the top bar): the title logo, one detail line, Play and More info, and a dot per slide;
 * [entry] lands focus on Play; [compact] draws the title and buttons smaller, for a short slot.
 * It moves on every [SLIDE_SHOW_MS] while it is on screen and the app is in front (not under the
 * player, a dialog or the screensaver); Left on Play and Right on the last button step through it
 * and restart the wait. Always on the dark look: the scrim under it stays dark in the light look.
 */
@Composable
internal fun HomeCarousel(slides: List<Slide>, marks: VodMarks, modifier: Modifier = Modifier, entry: Modifier = Modifier, compact: Boolean = false) {
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
        // The next backdrop (and its title logo) decodes while this one shows.
        val next = slides[(index + 1) % slides.size]
        launch { next.backdrop?.let { Backdrops.load(ctx, it) } }
        next.logo?.let { ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
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
        // The title and buttons sit just above the first row, which lies over the bottom of the art.
        val logo = if (compact) 56.gx else 80.gx
        val padV = if (compact) 8.gx else 10.gx
        Column(modifier.fillMaxWidth().padding(start = FOCUS_ROOM, bottom = 4.gx), verticalArrangement = Arrangement.spacedBy(if (compact) 8.gx else 10.gx, Alignment.Bottom)) {
            Box(Modifier.heightIn(min = logo), contentAlignment = Alignment.BottomStart) {
                if (slide.logo != null) {
                    AsyncImage(slide.logo, contentDescription = slide.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(logo).widthIn(max = 400.gx))
                } else {
                    Text(slide.title, fontSize = (if (compact) 30 else 40).gsp, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 620.gx))
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill("Play", play, entry.onFocusChanged { if (it.isFocused) focusedButton = 0 }, fill = Glass.Raised, border = true, fontSize = 17.gsp, padH = 26.gx, padV = padV)
                more?.let { GlassPill("More info", it, Modifier.onFocusChanged { f -> if (f.isFocused) focusedButton = 1 }, fill = Glass.Raised, border = true, fontSize = 17.gsp, padH = 26.gx, padV = padV) }
                // The dots beside the buttons, so the row under them can come up.
                if (slides.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.gx), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.gx)) {
                    slides.indices.forEach { i ->
                        Box(Modifier.size(if (i == index) 9.gx else 7.gx).background(if (i == index) Color.White else Color(0x66FFFFFF), CircleShape))
                    }
                }
            }
        }
    }
}

/** An event can be opened this long before it starts: its channel is usually showing the build-up. */
private const val EVENT_EARLY_MS = 15 * 60_000L

/**
 * Backdrops decoded off the main thread at the screen's size, in 16-bit colour (half the memory of
 * 32-bit; photos show no difference), held here rather than in Coil's small shared cache: rows of
 * posters used to push the next slide out of it, so the fade waited on a decode (onn jank) and
 * Home came back blank for a moment. Each bitmap is handed to the GPU ([Bitmap.prepareToDraw])
 * before it is drawn, so no fade frame waits on an upload. The slide on screen, the one fading out
 * and the next one: [KEEP] at most, about 12 MB at 1080p.
 */
internal object Backdrops {
    private const val KEEP = 3
    private val held = object : LinkedHashMap<String, ImageBitmap>(KEEP + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > KEEP
    }

    fun get(url: String): ImageBitmap? = synchronized(held) { held[url] }

    /** [url] decoded and on its way to the GPU, or null when it can't be loaded. */
    suspend fun load(ctx: android.content.Context, url: String): ImageBitmap? {
        get(url)?.let { return it }
        val m = ctx.resources.displayMetrics
        val request = ImageRequest.Builder(ctx).data(url).size(m.widthPixels, m.heightPixels)
            .allowHardware(false).allowRgb565(true).memoryCachePolicy(CachePolicy.DISABLED).build()
        val bitmap = (ctx.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return null
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap().also { synchronized(held) { held[url] = it } }
    }
}

/**
 * The carousel's backdrop, full-bleed behind the TV home's top bar and page: the slide's TMDB art
 * (or an event's art over a dark sports backdrop), with a dark scrim behind the carousel's text and
 * another under the first row ([slot] is the carousel's height below [pageTop]), deepening to
 * solid dark at the screen's bottom, while [show]. Only this reads [slide], so a new slide
 * redraws the backdrop and nothing else of the home. The new art fades in over the old in
 * [SLIDE_FADE_MS] with its alpha applied to its own pixels (no offscreen copy of the screen), and
 * the scrims sit in one cached layer; a plain cut when the system asks for less motion, or on a box
 * that can't keep up with the fade ([SlideFade]). No zoom or pan.
 */
@Composable
internal fun BoxScope.HomeBackdrop(slide: State<Slide?>, show: Boolean, covers: MutableState<Boolean>? = null, pageTop: FloatState? = null, slot: FloatState? = null) {
    val ctx = LocalContext.current
    val target = slide.value.takeIf { show }
    val still = LocalReducedMotion.current
    val shown by animateFloatAsState(if (target != null) 1f else 0f, tween(if (still) 0 else 400), label = "backdrop")
    // The slide in front with its decoded art, and the one it is fading in over (none once the fade ends).
    var front by remember { mutableStateOf<Shown?>(null) }
    var behind by remember { mutableStateOf<Shown?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == null || target === front?.slide) return@LaunchedEffect
        // The old art stays up until the new one is decoded: the fade never starts on a blank.
        val art = target.backdrop?.let { Backdrops.load(ctx, it) ?: return@LaunchedEffect }
        val fading = front != null && !still && !SlideFade.cuts(ctx)
        behind = front.takeIf { fading }
        front = Shown(target, art)
        if (fading) {
            fade.snapTo(0f)
            SlideFade.watch(ctx) { fade.animateTo(1f, tween(SLIDE_FADE_MS)) }
        }
        behind = null
    }
    // While the art is fully up it covers the whole screen, so the page background under it isn't drawn.
    val covered = shown == 1f && front != null
    if (covers != null) SideEffect { covers.value = covered }
    if (shown == 0f || front == null) return
    val density = LocalDensity.current
    val fullSlot = with(density) { CAROUSEL_HEIGHT.gx.toPx() }
    val gap = with(density) { ROW_GAP.gx.toPx() }
    val above = with(density) { 110.gx.toPx() }
    val heading = with(density) { 40.gx.toPx() }
    Box(Modifier.matchParentSize().graphicsLayer { alpha = shown; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
        listOfNotNull(behind, front).forEach { s ->
            androidx.compose.runtime.key(s.slide.key) {
                BackdropArt(s, if (s === front && behind != null) Modifier.graphicsLayer { alpha = fade.value; compositingStrategy = CompositingStrategy.ModulateAlpha } else Modifier)
            }
        }
        // Dark behind the text on the left whatever the look; below the carousel, the page's own colour.
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
                val dark = Color(0xE6060A14)
                val across = Brush.horizontalGradient(0f to dark, 0.45f to Color(0x99060A14), 0.75f to Color.Transparent)
                // The top bar's light text sits on this shade, whatever the art.
                // The first row lies over the art's lower part (Arshad, Oct 26): its heading and titles on a
                // dark shade that is solid by the bottom of the screen, in either look; the art shows through
                // it down to about three quarters of the way.
                val h = size.height
                val row = (pageTop?.floatValue ?: 0f).takeIf { it > 0f }
                    ?.let { it + ((slot?.floatValue ?: 0f).takeIf { s -> s > 0f } ?: fullSlot) + gap } ?: (h * 0.6f)
                val top = (row / h).coerceIn(0.35f, 0.9f)
                val clear = (top - above / h).coerceAtLeast(0.25f)
                val under = (top + heading / h).coerceAtMost(0.95f)
                val deep = maxOf(0.75f, under + 0.02f).coerceAtMost(0.97f)
                val down = Brush.verticalGradient(
                    0f to Color(0xCC060A14), 0.12f to Color(0x8C060A14), 0.24f to Color.Transparent, clear to Color.Transparent,
                    top to INK.copy(alpha = 0.7f), under to INK.copy(alpha = 0.82f), deep to INK.copy(alpha = 0.93f), 1f to INK,
                )
                onDrawBehind { drawRect(across); drawRect(down) }
            },
        )
    }
}

/**
 * Whether this box cross-fades the carousel's slides or cuts between them. Decided on the box, not
 * from a list of models: a low-RAM device cuts from the start; otherwise the first fades are
 * timed, and when [BAD_FADES] of the first [TRIED_FADES] miss more than [LATE_SHARE] of their
 * frames (the onn, 5-40%), it cuts from then on. Kept per app version, so an update times again.
 */
internal object SlideFade {
    private const val TRIED_FADES = 3
    private const val BAD_FADES = 2
    private const val LATE_SHARE = 0.1
    private const val KEY = "slideCut"
    @Volatile private var cut: Boolean? = null
    private var tried = 0
    private var bad = 0

    fun cuts(ctx: android.content.Context): Boolean = cut ?: run {
        val p = ctx.getSharedPreferences("featured", android.content.Context.MODE_PRIVATE)
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        (am?.isLowRamDevice == true || p.getInt(KEY, 0) == BuildConfig.VERSION_CODE).also { cut = it }
    }

    /** Runs [fade] while counting frames that came later than one and a half refreshes, while the first fades are still being timed. */
    suspend fun watch(ctx: android.content.Context, fade: suspend () -> Unit) {
        if (tried >= TRIED_FADES) return fade()
        @Suppress("DEPRECATION")
        val hz = (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay?.refreshRate?.takeIf { it >= 20f } ?: 60f
        val late = (1.5e9 / hz).toLong()
        var frames = 0
        var missed = 0
        coroutineScope {
            val clock = launch {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    frames++
                    if (now - last > late) missed++
                    last = now
                }
            }
            fade()
            clock.cancel()
        }
        tried++
        if (frames >= 5 && missed > frames * LATE_SHARE) bad++
        android.util.Log.i("SlideFade", "fade $tried: $missed of $frames frames late, ${if (bad >= BAD_FADES) "cutting from now on" else "fading"}")
        if (bad >= BAD_FADES) {
            cut = true
            ctx.getSharedPreferences("featured", android.content.Context.MODE_PRIVATE).edit().putInt(KEY, BuildConfig.VERSION_CODE).apply()
        }
    }
}

/** The shade under Home's rows while the carousel shows: the dark look's page colour. */
private val INK = GlassPalette.Dark.middle

/** A slide on the backdrop, with its art already decoded (null for an event without TMDB art). */
private class Shown(val slide: Slide, val art: ImageBitmap?)

@Composable
private fun BackdropArt(s: Shown, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        val art = s.art
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF07131F), Color(0xFF0B2A22), Color(0xFF060A14)))))
            s.slide.art?.let {
                // Below the top bar, clear of the search box.
                AsyncImage(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.TopEnd).padding(top = 190.gx, end = 90.gx).size(320.gx, 200.gx))
            }
        }
    }
}
