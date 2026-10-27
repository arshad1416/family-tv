package app.familytv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The tab icons' art from the Rive nav-icons scene, as plain paths: on an 80 x 80 artboard, centred on the
 * middle. [Mark.stroke] 0 fills the path. Drawn by [StillTabIcon] wherever Rive doesn't run.
 */
internal object TabArt {
    class Mark(val d: String, val stroke: Float, val alpha: Float = 1f)

    const val SIZE = 80f

    val marks: Map<String, List<Mark>> = mapOf(
        "Home" to listOf(
            Mark("M-19 -1L0 -18L19 -1", 3.2f),
            Mark("M-4.5 17L-4.5 6L4.5 6L4.5 17", 2.6f),
            Mark("M-14 -5L-14 17L14 17L14 -5", 3.2f),
        ),
        "Live TV" to listOf(
            Mark("M-13 -13.5H13A5 5 0 0 1 18 -8.5V6.5A5 5 0 0 1 13 11.5H-13A5 5 0 0 1 -18 6.5V-8.5A5 5 0 0 1 -13 -13.5Z", 3.2f),
            Mark("M-6 -19L0 -13.5L6 -19", 3.2f),
            Mark("M-9 17L9 17", 3.2f),
        ),
        "Movies" to listOf(
            Mark(
                "M-3.5 -10.5A3.5 3.5 0 1 1 3.5 -10.5A3.5 3.5 0 1 1 -3.5 -10.5Z" +
                    "M4.584 -4.627A3.5 3.5 0 1 1 11.584 -4.627A3.5 3.5 0 1 1 4.584 -4.627Z" +
                    "M1.496 4.877A3.5 3.5 0 1 1 8.496 4.877A3.5 3.5 0 1 1 1.496 4.877Z" +
                    "M-8.496 4.877A3.5 3.5 0 1 1 -1.496 4.877A3.5 3.5 0 1 1 -8.496 4.877Z" +
                    "M-11.584 -4.627A3.5 3.5 0 1 1 -4.584 -4.627A3.5 3.5 0 1 1 -11.584 -4.627Z" +
                    "M-1.8 -2A1.8 1.8 0 1 1 1.8 -2A1.8 1.8 0 1 1 -1.8 -2Z",
                0f,
            ),
            Mark("M-16 -2A16 16 0 1 1 16 -2A16 16 0 1 1 -16 -2Z", 3.2f),
            Mark("M0 14L20 14L20 9", 3.2f),
        ),
        "TV Shows" to listOf(
            Mark("M-10 -13L-6 -7M0 -13L4 -7M10 -13L13 -8.5", 2.4f),
            Mark("M-15 -13.5H15A2 2 0 0 1 17 -11.5V-8A2 2 0 0 1 15 -6H-15A2 2 0 0 1 -17 -8V-11.5A2 2 0 0 1 -15 -13.5Z", 3.2f),
            Mark("M-9 -3.5L-5 1M1 -3.5L5 1M11 -3.5L15 1", 2.4f),
            Mark("M-14 -3.5H14A3 3 0 0 1 17 -0.5V14.5A3 3 0 0 1 14 17.5H-14A3 3 0 0 1 -17 14.5V-0.5A3 3 0 0 1 -14 -3.5Z", 3.2f),
        ),
        "Sports" to listOf(
            Mark("M-16 0L16 0M0 -16L0 16M-9 -13C-4.334 -4.333 -4.334 4.333 -9 13M9 -13C4.334 -4.333 4.334 4.333 9 13", 2.3f),
            Mark("M-16 0A16 16 0 1 1 16 0A16 16 0 1 1 -16 0Z", 3.2f),
            Mark("M-9 20A9 2 0 1 1 9 20A9 2 0 1 1 -9 20Z", 0f, alpha = 0.3f),
        ),
    )

    /** The scene's accent: the selected dot, and the two glows at their own strength. */
    val Accent = Color(0xFF1F6BFF)
    const val FOCUS_GLOW_ALPHA = 0x73 / 255f
    const val SELECTED_GLOW_ALPHA = 0x4C / 255f

    /** The art's ink, as the scene sets it: dark on the white pill in the dark look, white on the dark pill in the light look. */
    fun ink(selected: Boolean, light: Boolean): Color = if (selected != light) Color(0xFF0B0F18) else Color.White
}

/**
 * A tab's icon drawn from [TabArt] with the same moves as its Rive icon: the ink is a little dim until [focused],
 * when it also grows a touch and a soft glow comes up; [selected] brings up its own glow and the dot under it.
 * Scale and opacity only, and no blur: the glows are radial fades. With [reducedMotion] each change lands at once.
 */
@Composable
internal fun StillTabIcon(label: String, focused: Boolean, selected: Boolean, light: Boolean, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val marks = remember(label) {
        TabArt.marks[label].orEmpty().map { m ->
            val path = Path()
            PathParser().parsePathString(m.d).toPath(path)
            Triple(path, m.stroke, m.alpha)
        }
    }
    val focus by animateFloatAsState(if (focused) 1f else 0f, if (reducedMotion) snap() else tween(150), label = "focus")
    val chosen by animateFloatAsState(if (selected) 1f else 0f, if (reducedMotion) snap() else tween(180), label = "selected")
    val ink = TabArt.ink(selected, light)
    Canvas(modifier) {
        val unit = size.minDimension / TabArt.SIZE
        translate(size.width / 2, size.height / 2) {
            withTransform({ scale(unit * (1f + 0.06f * focus), unit * (1f + 0.06f * focus), Offset.Zero) }) {
                val inkAlpha = 0.82f + 0.18f * focus
                marks.forEach { (path, stroke, alpha) ->
                    drawPath(
                        path, ink, alpha * inkAlpha,
                        if (stroke == 0f) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                if (chosen > 0f) {
                    val w = 10f * (0.2f + 0.8f * chosen)
                    drawRoundRect(TabArt.Accent, Offset(-w / 2, 31f - 1.75f), Size(w, 3.5f), CornerRadius(1.75f), alpha = chosen)
                }
                glow(25f, TabArt.FOCUS_GLOW_ALPHA * focus)
                glow(23f, TabArt.SELECTED_GLOW_ALPHA * chosen)
            }
        }
    }
}

/** A feathered disc of the accent, [radius] across half its width plus the scene's 12-unit feather, centred. */
private fun DrawScope.glow(radius: Float, alpha: Float) {
    if (alpha <= 0f) return
    val outer = radius + 6f
    val solid = ((radius - 6f) / outer).coerceAtLeast(0f)
    drawCircle(
        Brush.radialGradient(0f to TabArt.Accent.copy(alpha = alpha), solid to TabArt.Accent.copy(alpha = alpha), 1f to Color.Transparent, center = Offset.Zero, radius = outer),
        outer, Offset.Zero,
    )
}
