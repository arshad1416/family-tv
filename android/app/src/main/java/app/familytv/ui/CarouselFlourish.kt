package app.familytv.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** The carousel's slide-change flourishes: a sweep of light for films, a glow for shows, streaks for events. */
internal enum class Flourish { SWEEP, GLOW, STREAKS }

/** How long a flourish runs, over the [SLIDE_FADE_MS] fade. */
internal const val FLOURISH_MS = 800

private val LIGHT = Color(0xFFBFD8FF)

/**
 * Light over the carousel's art while it fades, after the Rive design (rive/carousel: same shapes,
 * colours and paths; the Rive view itself cost the Fire Stick 80% janky frames). Each piece is a
 * small cached layer that only moves, scales and fades ([progress], 0 to 1, read at draw time), so
 * a frame redraws nothing; the sweep and streaks cover a fraction of the screen.
 */
@Composable
internal fun BoxScope.CarouselFlourish(style: Flourish, progress: () -> Float) {
    // Mockup px (1280 across) in screen px, read outside the draw lambdas.
    val u = with(LocalDensity.current) { 1.gx.toPx() }
    val w = 1280 * u
    when (style) {
        Flourish.SWEEP -> {
            // A band of light leaning right crosses the screen from the left, its leading edge brighter.
            Spacer(
                Modifier.align(Alignment.CenterStart).size(373.gx, 900.gx)
                    .graphicsLayer {
                        val p = progress()
                        translationX = -size.width + (w + size.width) * ease(p)
                        rotationZ = 11.5f
                        alpha = rise(p)
                    }
                    .drawWithCache {
                        val glow = Brush.horizontalGradient(
                            0f to Color.Transparent, 0.42f to LIGHT.copy(alpha = 0.22f), 0.5f to Color.White.copy(alpha = 0.34f),
                            0.58f to LIGHT.copy(alpha = 0.22f), 1f to Color.Transparent,
                        )
                        val edge = Size(4 * u, size.height)
                        onDrawBehind {
                            drawRect(glow)
                            drawRect(LIGHT.copy(alpha = 0.6f), topLeft = Offset(size.width * 0.58f, 0f), size = edge)
                        }
                    },
            )
        }
        Flourish.GLOW -> {
            // A soft bloom swells behind the title side of the art, with a thin ring running ahead of it.
            Box(Modifier.align(Alignment.TopStart).fillMaxSize()) {
                Spacer(
                    Modifier.size(700.gx, 500.gx)
                        .graphicsLayer {
                            val p = progress()
                            translationX = 819 * u - size.width / 2
                            translationY = 274 * u - size.height / 2
                            val s = 0.5f + 0.6f * ease(p)
                            scaleX = s; scaleY = s
                            alpha = rise(p)
                        }
                        .drawWithCache {
                            val bloom = Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.32f), 0.45f to LIGHT.copy(alpha = 0.16f), 0.75f to Color(0x0D1F6BFF), 1f to Color.Transparent,
                                radius = size.minDimension / 2,
                            )
                            onDrawBehind { drawOval(bloom) }
                        },
                )
                Spacer(
                    Modifier.size(600.gx, 427.gx)
                        .graphicsLayer {
                            val p = progress()
                            translationX = 819 * u - size.width / 2
                            translationY = 274 * u - size.height / 2
                            val s = 0.3f + 1.1f * ease(p)
                            scaleX = s; scaleY = s
                            alpha = rise(p) * 0.8f
                        }
                        .drawBehind { drawOval(LIGHT.copy(alpha = 0.55f), style = Stroke(2 * u)) },
                )
            }
        }
        Flourish.STREAKS -> {
            // Three streaks of light race in from the right, the longest slowest, like a stadium's lights.
            Box(Modifier.align(Alignment.TopStart).fillMaxSize()) {
                Streak(600.gx, 7.gx, 201 * u, 0.7f, 1.05f, w, progress)
                Streak(867.gx, 12.gx, 338 * u, 0.55f, 0.9f, w, progress)
                Streak(467.gx, 4.gx, 475 * u, 0.8f, 1.2f, w, progress)
            }
        }
    }
}

@Composable
private fun Streak(length: Dp, thick: Dp, y: Float, peak: Float, speed: Float, w: Float, progress: () -> Float) {
    Spacer(
        Modifier.size(length, thick)
            .graphicsLayer {
                val p = progress()
                translationX = w - (w + size.width * 2) * ease((p * speed).coerceAtMost(1f))
                translationY = y
                alpha = rise(p)
            }
            .drawWithCache {
                val fill = Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.White.copy(alpha = peak), 0.3f to LIGHT.copy(alpha = peak * 0.5f), 1f to Color.Transparent)
                onDrawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2)) }
            },
    )
}

/** Ease in and out. */
private fun ease(p: Float): Float = p * p * (3 - 2 * p)

/** In over the first fifth, out over the last third. */
private fun rise(p: Float): Float = minOf(1f, p / 0.2f, (1f - p) / 0.33f).coerceIn(0f, 1f)

/**
 * Counts the app's frames while [block] runs, and how many of them were late: a frame that came a
 * refresh or more after the one it should have followed (a skipped vsync, as `dumpsys gfxinfo`
 * counts jank), or that took over two refreshes to finish. Android's own frame times where it
 * reports them; before Android 7, the gaps between frames.
 */
internal suspend fun timeFrames(ctx: Context, block: suspend () -> Unit): Pair<Int, Int> {
    @Suppress("DEPRECATION")
    val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay?.refreshRate?.takeIf { it >= 20f } ?: 60f
    val refresh = (1e9 / hz).toLong()
    val window = ctx.findActivity()?.window
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && window != null) {
        val frames = AtomicInteger()
        val late = AtomicInteger()
        var last = 0L
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            // One thread delivers them all, in order.
            val skipped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                (last != 0L && vsync - last > refresh * 3 / 2).also { last = vsync }
            } else {
                false
            }
            frames.incrementAndGet()
            if (skipped || m.getMetric(FrameMetrics.TOTAL_DURATION) > refresh * 2) late.incrementAndGet()
        }
        window.addOnFrameMetricsAvailableListener(listener, metrics)
        try {
            block()
        } finally {
            window.removeOnFrameMetricsAvailableListener(listener)
        }
        return frames.get() to late.get()
    }
    var frames = 0
    var late = 0
    coroutineScope {
        val clock = launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                frames++
                if (now - last > refresh * 3 / 2) late++
                last = now
            }
        }
        block()
        clock.cancel()
    }
    return frames to late
}

private val metrics by lazy { Handler(HandlerThread("FrameTimes").apply { start() }.looper) }
