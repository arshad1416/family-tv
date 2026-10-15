package app.familytv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** How the picture fills the screen. [ratio] forces that shape; the others follow the video's own. */
enum class Aspect(val label: String, val hint: String, val ratio: Float? = null) {
    Fit("Original", "The whole picture; bars if its shape differs from the screen"),
    Fill("Zoom to fill", "Fills the screen, cutting off the edges"),
    Stretch("Stretch", "Fills the screen, stretching the picture"),
    Wide("16:9", "Shows it as widescreen", 16f / 9f),
    Classic("4:3", "Shows it as an older, squarer TV picture", 4f / 3f),
}

/** The picture format picked in the player, kept for every channel and video after it. */
class AspectPref(context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    var value by mutableStateOf(Aspect.entries.firstOrNull { it.name == prefs.getString("aspect", null) } ?: Aspect.Fit)
        private set

    fun set(a: Aspect) {
        prefs.edit().putString("aspect", a.name).apply()
        value = a
    }
}

/** Side and top margins that shrink a [w] x [h] frame to the largest centred box of [ratio]. */
fun boxMargins(w: Int, h: Int, ratio: Float): Pair<Int, Int> = when {
    w <= 0 || h <= 0 -> 0 to 0
    w.toFloat() / h > ratio -> ((w - h * ratio) / 2).roundToInt() to 0
    else -> 0 to ((h - w / ratio) / 2).roundToInt()
}
