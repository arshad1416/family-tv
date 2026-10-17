package app.familytv.ui

import android.net.Uri
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.familytv.R
import kotlinx.coroutines.delay

/**
 * The startup video: The Nook's on the Airbnb guest profile (the portal's profile setting
 * `splash` = "the-nook"), Family TV's on every other, in the season of the TV's month.
 */
object Splash {
    const val NOOK = "the-nook"

    enum class Season { SPRING, SUMMER, FALL, WINTER }

    /** [month] is 1 (January) to 12. */
    fun season(month: Int): Season = when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.FALL
        else -> Season.WINTER
    }

    @RawRes
    fun video(profileSplash: String?, month: Int): Int {
        val nook = profileSplash == NOOK
        return when (season(month)) {
            Season.SPRING -> if (nook) R.raw.the_nook_splash_spring else R.raw.family_tv_splash_spring
            Season.SUMMER -> if (nook) R.raw.the_nook_splash_summer else R.raw.family_tv_splash_summer
            Season.FALL -> if (nook) R.raw.the_nook_splash_fall else R.raw.family_tv_splash_fall
            Season.WINTER -> if (nook) R.raw.the_nook_splash_winter else R.raw.family_tv_splash_winter
        }
    }
}

/**
 * Plays [video] over the app while it starts underneath, then fades out. Every video ends on the
 * app's background colour, so the fade shows no edge. [skipped] (any remote key, or a tap) and a
 * video that won't play end it early; [onDone] runs once it has faded.
 */
@Composable
fun SplashVideo(@RawRes video: Int, skipped: Boolean, onSkip: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${ctx.packageName}/$video")))
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onSkip() }
            override fun onPlayerError(error: PlaybackException) = onSkip()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    // A stuck decoder must not hold the app behind the video.
    LaunchedEffect(Unit) { delay(12_000); onSkip() }
    val alpha by animateFloatAsState(if (skipped) 0f else 1f, tween(400), label = "splash", finishedListener = { if (skipped) onDone() })
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Tv.Bg).pointerInput(Unit) { detectTapGestures { onSkip() } },
        contentAlignment = Alignment.Center,
    ) {
        // A TextureView, unlike PlayerView's SurfaceView, fades with the layer above.
        AndroidView({ TextureView(it).also(player::setVideoTextureView) }, Modifier.aspectRatio(16f / 9f))
    }
}
