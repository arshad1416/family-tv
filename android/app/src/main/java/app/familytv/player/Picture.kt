package app.familytv.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/** How the video fills the screen; one choice for every channel, movie and episode. */
@androidx.annotation.OptIn(UnstableApi::class)
enum class Picture(val label: String, val detail: String, val resizeMode: Int) {
    Fit("Fit", "The whole picture, with black bars when its shape differs", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Stretch("Stretch", "Fills the screen; people may look wider or taller", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("Zoom", "Fills the screen and trims the edges", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
        fun load(c: Context) = entries.firstOrNull { it.name == prefs(c).getString("picture", null) } ?: Fit
        fun save(c: Context, p: Picture) = prefs(c).edit().putString("picture", p.name).apply()
    }
}
