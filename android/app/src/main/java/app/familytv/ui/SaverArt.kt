package app.familytv.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Space and Earth": NASA photos (public domain), picked by hand from NASA's image library so
 * every one is a scene, not a press photo. 1920-wide JPEGs from images-assets.nasa.gov; the wide
 * ones fill the screen, the square ones show whole on black.
 */
internal object SpaceArt {
    private fun nasa(id: String, caption: String, wide: Boolean) =
        SaverPicture("https://images-assets.nasa.gov/image/$id/$id~large.jpg", wide, "$caption · NASA")

    val pictures = listOf(
        nasa("GSFC_20171208_Archive_e001955", "The Lagoon Nebula, Hubble", true),
        nasa("GSFC_20171208_Archive_e002076", "The Carina Nebula, Hubble", true),
        nasa("GSFC_20171208_Archive_e002152", "Inside the Carina Nebula, Hubble", true),
        nasa("PIA03606", "The Crab Nebula, Hubble", false),
        nasa("PIA17563", "The Crab Nebula, Herschel and Hubble", false),
        nasa("PIA14415", "The Tarantula Nebula", false),
        nasa("GSFC_20171208_Archive_e002039", "Bubbles and baby stars, Hubble", false),
        nasa("PIA18164", "The Helix Nebula", false),
        nasa("PIA16008", "The Horsehead Nebula, Hubble", false),
        nasa("PIA15985", "Mystic Mountain, Hubble", false),
        nasa("GSFC_20171208_Archive_e000383", "The Bubble Nebula, Hubble", false),
        nasa("GSFC_20171208_Archive_e001885", "A rose of galaxies, Hubble", false),
        nasa("PIA09579", "The galaxy M81", false),
        nasa("GSFC_20171208_Archive_e001925", "The Whirlpool Galaxy, Hubble", false),
        nasa("PIA25432", "Dust rings around Wolf-Rayet 140, Webb", true),
        nasa("iss043e000030", "Earth from the International Space Station", true),
        nasa("iss042e034845", "Earth from the International Space Station", true),
        nasa("iss035e014466", "Earth from the International Space Station", true),
        nasa("iss040e111251", "An aurora from the International Space Station", true),
        nasa("iss030e119777", "An aurora from the International Space Station", true),
        nasa("iss029e012564", "An aurora and city lights from the International Space Station", true),
        nasa("iss040e018989", "Earth's edge at night from the International Space Station", true),
        nasa("art002e021278", "Earthset over the Moon, Artemis II", true),
        nasa("GSFC_20171208_Archive_e001788", "The Blue Marble, 2012", false),
        nasa("GSFC_20171208_Archive_e002131", "The Blue Marble, 2007", false),
        nasa("GSFC_20171208_Archive_e001386", "The Blue Marble, Americas", false),
        nasa("GSFC_20171208_Archive_e001587", "The Americas at night", false),
        nasa("GSFC_20171208_Archive_e002000", "Susitna Glacier, Alaska", false),
    )
}

/** How a season's scene looks: its sky top to bottom, sun or moon, hills, trees and what drifts down. */
private class SeasonLook(
    val sky: List<Color>,
    val glow: Color,
    val glowAt: Offset,
    val far: Color,
    val near: Color,
    val trunk: Color,
    /** Leaves on the trees, or none for bare branches. */
    val canopy: List<Color>?,
    val drift: Drift,
    val colours: List<Color>,
    val stars: Boolean,
    /** Sideways drift, in screen widths a second. */
    val wind: Float = 0f,
) {
    enum class Drift { SNOW, PETALS, LEAVES, FIREFLIES }

    companion object {
        fun of(season: Splash.Season) = when (season) {
            Splash.Season.SPRING -> SeasonLook(
                listOf(Color(0xFF1E2A4F), Color(0xFF5D6FA8), Color(0xFFE9A9B9)), Color(0xFFFFE3C4), Offset(0.7f, 0.42f),
                Color(0xFF2B3A5A), Color(0xFF1A2440), Color(0xFF151018), listOf(Color(0xFFF3A6C0), Color(0xFFF8C8D8)),
                Drift.PETALS, listOf(Color(0xFFF8C8D8), Color(0xFFF3A6C0), Color(0xFFFFF4F7)), stars = false, wind = 0.006f,
            )
            Splash.Season.SUMMER -> SeasonLook(
                listOf(Color(0xFF0B1A33), Color(0xFF23476E), Color(0xFFF0A35E)), Color(0xFFFFD49A), Offset(0.3f, 0.6f),
                Color(0xFF1D2B3F), Color(0xFF101A28), Color(0xFF0C1218), listOf(Color(0xFF2F6B3E), Color(0xFF3E8150)),
                Drift.FIREFLIES, listOf(Color(0xFFF6F3A0)), stars = true,
            )
            Splash.Season.FALL -> SeasonLook(
                listOf(Color(0xFF140C14), Color(0xFF4A2418), Color(0xFFC86A32)), Color(0xFFFFC27A), Offset(0.5f, 0.52f),
                Color(0xFF2A1A16), Color(0xFF181010), Color(0xFF120A0A), listOf(Color(0xFFD9772F), Color(0xFFB8462A)),
                Drift.LEAVES, listOf(Color(0xFFE07B2E), Color(0xFFC9452A), Color(0xFFE8B13A)), stars = false, wind = 0.01f,
            )
            Splash.Season.HALLOWEEN -> SeasonLook(
                listOf(Color(0xFF05030C), Color(0xFF1E0F33), Color(0xFF4B2366)), Color(0xFFF5E6B8), Offset(0.72f, 0.3f),
                Color(0xFF140C1E), Color(0xFF0A0610), Color(0xFF07040A), null,
                Drift.LEAVES, listOf(Color(0xFFE07B2E), Color(0xFF8E4FB5)), stars = true, wind = 0.008f,
            )
            Splash.Season.WINTER -> SeasonLook(
                listOf(Color(0xFF050A18), Color(0xFF132648), Color(0xFF3E5F8E)), Color(0xFFE8F0FF), Offset(0.25f, 0.28f),
                Color(0xFF8FA2BD), Color(0xFFC9D5E4), Color(0xFF2A3245), null,
                Drift.SNOW, listOf(Color.White), stars = true, wind = 0.003f,
            )
        }
    }
}

/** One drifting snowflake, petal, leaf or firefly; positions are fractions of the screen. */
private class Mote(r: Random) {
    val x = r.nextFloat()
    val y = r.nextFloat()
    val speed = 0.02f + r.nextFloat() * 0.04f
    val size = 0.5f + r.nextFloat()
    val phase = r.nextFloat() * 6.283f
    val rate = 0.3f + r.nextFloat() * 0.5f
    val colour = r.nextInt(3)
}

private const val MOTES = 40

/**
 * "Seasonal scenes": the splash's season drawn in code, without its logo: a sky, a sun or moon,
 * two hills and two trees, with snow, petals, leaves or fireflies drifting across at 30 frames a
 * second. Still when the system asks for less motion.
 */
@Composable
internal fun SeasonScene(season: Splash.Season) {
    val still = rememberReducedMotion()
    val look = remember(season) { SeasonLook.of(season) }
    val motes = remember(season) { List(MOTES) { Mote(Random(it * 7919 + season.ordinal)) } }
    val stars = remember(season) { Random(season.ordinal).let { r -> List(70) { Offset(r.nextFloat(), r.nextFloat() * 0.5f) to 0.5f + r.nextFloat() } } }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var start = -1L
        var last = 0L
        while (true) withFrameMillis { ms ->
            if (start < 0) start = ms
            if (ms - last >= 33) { last = ms; time.floatValue = (ms - start) / 1000f }
        }
    }
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val sky = Brush.verticalGradient(look.sky, endY = h * 0.8f)
            val sun = Offset(look.glowAt.x * w, look.glowAt.y * h)
            val halo = Brush.radialGradient(listOf(look.glow.copy(alpha = 0.45f), Color.Transparent), center = sun, radius = h * 0.45f)
            val shade = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))
            val far = Path().apply {
                moveTo(0f, h * 0.78f)
                cubicTo(w * 0.25f, h * 0.7f, w * 0.5f, h * 0.8f, w * 0.75f, h * 0.74f)
                cubicTo(w * 0.88f, h * 0.71f, w * 0.95f, h * 0.75f, w, h * 0.73f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            val near = Path().apply {
                moveTo(0f, h * 0.86f)
                quadraticBezierTo(w * 0.3f, h * 0.8f, w * 0.6f, h * 0.87f)
                quadraticBezierTo(w * 0.8f, h * 0.91f, w, h * 0.84f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            onDrawBehind {
                val t = time.floatValue
                drawRect(sky)
                if (look.stars) stars.forEach { (p, s) ->
                    val twinkle = if (still) 0.6f else 0.45f + 0.35f * sin(t * 0.8f + p.x * 40f)
                    drawCircle(Color.White.copy(alpha = twinkle), radius = h * 0.0016f * s, center = Offset(p.x * w, p.y * h))
                }
                drawCircle(halo, radius = h * 0.45f, center = sun)
                drawCircle(look.glow, radius = h * 0.055f, center = sun)
                drawPath(far, look.far)
                drawPath(near, look.near)
                tree(look, Offset(w * 0.12f, h * 0.84f), h * 0.22f)
                tree(look, Offset(w * 0.86f, h * 0.83f), h * 0.26f)
                motes.forEach { drawMote(look, it, t, w, h) }
                drawRect(shade)
            }
        },
    )
}

private fun DrawScope.tree(look: SeasonLook, base: Offset, height: Float) {
    val top = Offset(base.x, base.y - height * 0.6f)
    val stroke = height * 0.05f
    drawLine(look.trunk, base, top, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(look.trunk, Offset(base.x, base.y - height * 0.35f), Offset(base.x - height * 0.22f, base.y - height * 0.7f), strokeWidth = stroke * 0.6f, cap = StrokeCap.Round)
    drawLine(look.trunk, Offset(base.x, base.y - height * 0.45f), Offset(base.x + height * 0.25f, base.y - height * 0.78f), strokeWidth = stroke * 0.6f, cap = StrokeCap.Round)
    drawLine(look.trunk, top, Offset(base.x + height * 0.05f, base.y - height * 0.9f), strokeWidth = stroke * 0.5f, cap = StrokeCap.Round)
    val leaves = look.canopy ?: return
    val r = height * 0.2f
    listOf(Offset(-0.22f, -0.72f), Offset(0.24f, -0.78f), Offset(0f, -0.88f), Offset(-0.1f, -0.66f), Offset(0.12f, -0.68f)).forEachIndexed { i, o ->
        drawCircle(leaves[i % leaves.size], radius = r * (if (i == 2) 1.1f else 0.9f), center = Offset(base.x + o.x * height, base.y + o.y * height))
    }
}

private fun DrawScope.drawMote(look: SeasonLook, m: Mote, t: Float, w: Float, h: Float) {
    val colour = look.colours[m.colour % look.colours.size]
    if (look.drift == SeasonLook.Drift.FIREFLIES) {
        val x = (m.x + sin(t * 0.4f * m.rate + m.phase) * 0.04f) * w
        val y = (0.45f + m.y * 0.45f + cos(t * 0.17f + m.phase) * 0.03f) * h
        val glow = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * 1.5f * m.rate + m.phase))
        drawCircle(colour.copy(alpha = 0.25f * glow), radius = h * 0.012f * m.size, center = Offset(x, y))
        drawCircle(colour.copy(alpha = glow), radius = h * 0.003f * m.size, center = Offset(x, y))
        return
    }
    val y = ((m.y + t * m.speed) % 1.1f - 0.05f) * h
    val x = (((m.x + sin(t * m.rate + m.phase) * 0.02f + t * look.wind) % 1f + 1f) % 1f) * w
    when (look.drift) {
        SeasonLook.Drift.SNOW -> drawCircle(colour.copy(alpha = 0.85f), radius = h * 0.004f * m.size, center = Offset(x, y))
        else -> {
            val leaf = Size(h * 0.018f * m.size, h * 0.009f * m.size)
            rotate(m.phase * 57f + t * 60f * m.rate, Offset(x, y)) {
                drawOval(colour, topLeft = Offset(x - leaf.width / 2, y - leaf.height / 2), size = leaf)
            }
        }
    }
}
