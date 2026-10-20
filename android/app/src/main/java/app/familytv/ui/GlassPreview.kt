package app.familytv.ui

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.familytv.app
import app.familytv.core.Http
import app.familytv.data.ChannelEntity
import app.familytv.iptv.Streams
import kotlinx.coroutines.delay

/** How long focus rests on a channel before its preview starts. */
private const val PREVIEW_DELAY_MS = 1_500L

/**
 * The live preview on the TV's Live TV and Sports pages: the focused channel plays in the preview
 * box once focus has rested on it for [PREVIEW_DELAY_MS]. Lines allow one connection, so it
 * stops when focus moves on, when the app goes to the background, and (via [stop]) before a
 * channel opens full screen. Until then the box shows the programme's picture and Now/Next.
 */
internal class LivePreview(private val context: Context) {
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null

    /** True from the moment a channel starts loading until it stops: the box shows the video. */
    var active by mutableStateOf(false)
        private set

    companion object {
        /** Whether any preview box is showing video, so the screensaver waits. */
        var anyActive by mutableStateOf(false)
            internal set
    }

    /** Bumped when the app comes back, so the focused channel starts again. */
    var resumed by mutableIntStateOf(0)
        private set

    @OptIn(UnstableApi::class)
    private fun ensure(): ExoPlayer = player ?: run {
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    // A preview that won't play just stays a still card; full screen has the recovery.
                    override fun onPlayerError(error: PlaybackException) = stop()
                })
                player = p
                view?.player = p
            }
    }

    /** Plays [channel] in the box. */
    suspend fun play(channel: ChannelEntity) {
        val profile = context.app.portal.cachedConfig?.profile ?: return
        val url = Streams(context.app.db, profile).live(channel) ?: return
        val p = ensure()
        active = true
        anyActive = true
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    /** Stops the stream, freeing the line's connection. */
    fun stop() {
        active = false
        anyActive = false
        player?.run { stop(); clearMediaItems() }
    }

    fun release() {
        stop()
        view?.player = null
        player?.release()
        player = null
    }

    fun onResume() { resumed++ }

    @OptIn(UnstableApi::class)
    fun view(context: Context): View = view ?: PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        isFocusable = false
        isFocusableInTouchMode = false
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        player = this@LivePreview.player
        view = this
    }
}

/** A [LivePreview] for this page, released when the page goes and stopped while the app is in the background. */
@Composable
internal fun rememberLivePreview(): LivePreview {
    val ctx = LocalContext.current
    val preview = remember { LivePreview(ctx.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watch = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> preview.stop()
                Lifecycle.Event.ON_START -> preview.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch); preview.release() }
    }
    return preview
}

/** Plays [channel] once focus has rested on it ([channel] is null while focus is elsewhere). */
@Composable
internal fun PreviewFollows(preview: LivePreview, channel: ChannelEntity?) {
    LaunchedEffect(channel?.sourceId, channel?.streamId, preview.resumed) {
        preview.stop()
        val ch = channel ?: return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        preview.play(ch)
    }
}

/** The preview's video, for the box to show over its picture while [LivePreview.active]. */
@Composable
internal fun PreviewVideo(preview: LivePreview, modifier: Modifier) {
    AndroidView(factory = { preview.view(it) }, modifier = modifier)
}
