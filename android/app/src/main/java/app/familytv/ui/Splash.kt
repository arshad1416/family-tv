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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.RiveEvent
import kotlinx.coroutines.delay

/**
 * The startup splash: The Nook's on the Airbnb guest profile (the portal's profile setting
 * `splash` = "the-nook"), Family TV's on every other, in the season of the TV's month. It plays as
 * a Rive animation ([SplashRive]), or as the matching video where Rive can't run ([SplashVideo]).
 */
object Splash {
    const val NOOK = "the-nook"

    /** In the order of the Rive file's `season` input (0 spring to 3 winter). */
    enum class Season { SPRING, SUMMER, FALL, WINTER }

    /** Which splash to show: [season] and whether it is [nook]'s. */
    data class Choice(val season: Season, val nook: Boolean) {
        /** The video that stands in for the Rive splash. */
        @get:RawRes val video: Int get() = video(nook, season)
    }

    /** [month] is 1 (January) to 12. */
    fun choice(profileSplash: String?, month: Int) = Choice(season(month), profileSplash == NOOK)

    /** [month] is 1 (January) to 12. */
    fun season(month: Int): Season = when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.FALL
        else -> Season.WINTER
    }

    @RawRes
    fun video(profileSplash: String?, month: Int): Int = video(profileSplash == NOOK, season(month))

    @RawRes
    private fun video(nook: Boolean, season: Season): Int = when (season) {
        Season.SPRING -> if (nook) R.raw.the_nook_splash_spring else R.raw.family_tv_splash_spring
        Season.SUMMER -> if (nook) R.raw.the_nook_splash_summer else R.raw.family_tv_splash_summer
        Season.FALL -> if (nook) R.raw.the_nook_splash_fall else R.raw.family_tv_splash_fall
        Season.WINTER -> if (nook) R.raw.the_nook_splash_winter else R.raw.family_tv_splash_winter
    }
}

/** The splash for [choice]: the Rive animation, or its video where Rive can't run or the file won't play. */
@Composable
fun SplashAnimation(choice: Splash.Choice, skipped: Boolean, onSkip: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var rive by remember { mutableStateOf(RiveArt.ready(ctx)) }
    if (rive) SplashRive(choice, skipped, onSkip, onDone, onFail = { rive = false })
    else SplashVideo(choice.video, skipped, onSkip, onDone)
}

/**
 * The Rive splash for [choice] over the app while it starts underneath. It fires its "done" event
 * at about 7 s, ending on the app's background colour; [onFail] hands over to the video.
 */
@Composable
private fun SplashRive(choice: Splash.Choice, skipped: Boolean, onSkip: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
    SplashFrame(skipped, onSkip, onDone) {
        AndroidView(
            factory = { c ->
                RiveAnimationView(c).apply {
                    isFocusable = false
                    addEventListener(object : RiveFileController.RiveEventListener {
                        override fun notifyEvent(event: RiveEvent) { if (event.name == "done") post(onSkip) }
                    })
                    runCatching {
                        setRiveResource(R.raw.family_tv_splash, stateMachineName = "Splash", fit = Fit.CONTAIN)
                        setNumberState("Splash", "season", choice.season.ordinal.toFloat())
                        setNumberState("Splash", "brand", if (choice.nook) 1f else 0f)
                    }.onFailure { post(onFail) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Plays [video] over the app while it starts underneath. Every video ends on the app's background colour. */
@Composable
private fun SplashVideo(@RawRes video: Int, skipped: Boolean, onSkip: () -> Unit, onDone: () -> Unit) {
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
    SplashFrame(skipped, onSkip, onDone) {
        // A TextureView, unlike PlayerView's SurfaceView, fades with the layer above.
        AndroidView({ TextureView(it).also(player::setVideoTextureView) }, Modifier.aspectRatio(16f / 9f))
    }
}

/**
 * The splash's frame: [content] on the app's background, faded out in 0.4 s once [skipped] (any
 * remote key, a tap, the end, or 12 s so a stuck splash never holds the app); [onDone] runs once
 * it has faded.
 */
@Composable
private fun SplashFrame(skipped: Boolean, onSkip: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) {
    LaunchedEffect(Unit) { delay(12_000); onSkip() }
    val alpha by animateFloatAsState(if (skipped) 0f else 1f, tween(400), label = "splash", finishedListener = { if (skipped) onDone() })
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Tv.Bg).pointerInput(Unit) { detectTapGestures { onSkip() } },
        contentAlignment = Alignment.Center,
    ) { content() }
}
