package app.familytv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.familytv.app
import app.familytv.core.Http
import app.familytv.data.ChannelEntity
import app.familytv.iptv.Streams
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen player. Live TV: D-pad up/down (or channel keys) zaps through the category.
 * When a stream fails it falls back from .ts to .m3u8 once, then runs a network check and
 * shows the person a plain-language reason instead of a spinner.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private var hideOverlay: Job? = null

    private var channels: List<ChannelEntity> = emptyList()
    private var index = 0
    private var triedHls = false
    private var profile: Profile? = null
    private var title = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        profile = app.portal.cachedConfig?.profile

        // Bigger buffer than the default for live IPTV: survives short Wi-Fi dips on sticks.
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000).build()
        val dataSource = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) PlaybackStatus.bufferingEvents++
                if (state == Player.STATE_READY) overlay.takeIf { it.tag == "error" }?.let { it.visibility = TextView.GONE; it.tag = null }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(t: AnalyticsListener.EventTime, loadMs: Int, bytes: Long, bitrate: Long) {
                PlaybackStatus.bitrateKbps = (bitrate / 1000).toInt()
            }
            override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
                PlaybackStatus.droppedFrames += dropped
            }
        })

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 20, 32, 20)
            visibility = TextView.GONE
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val overlayParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
        overlayParams.setMargins(48, 48, 48, 48)
        root.addView(overlay, overlayParams)
        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            view.useController = true
            start(url, title)
        } else {
            view.useController = false
            lifecycleScope.launch {
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: return@launch finish()
                val cat = intent.getStringExtra(EXTRA_CATEGORY) ?: return@launch finish()
                channels = app.db.catalog().channels(src, cat).first()
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
                playChannel()
            }
        }
    }

    private fun playChannel() {
        val ch = channels.getOrNull(index) ?: return
        val p = profile ?: return
        triedHls = false
        lifecycleScope.launch {
            val url = Streams(app.db, p).live(ch) ?: return@launch
            title = "${ch.num}  ${ch.name}"
            start(url, title)
            show(title, 3000)
        }
    }

    private fun start(url: String, t: String) {
        PlaybackStatus.started(t)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun onError(error: PlaybackException) {
        PlaybackStatus.error("${error.errorCodeName} on $title")
        val ch = channels.getOrNull(index)
        val p = profile
        // Some panels only serve HLS for live; retry once before telling anyone.
        if (ch != null && ch.url == null && !triedHls && p != null) {
            triedHls = true
            lifecycleScope.launch { Streams(app.db, p).live(ch, "m3u8")?.let { start(it, title) } }
            return
        }
        show("Can't play this right now. Checking why…", 0, isError = true)
        if (p == null) return
        lifecycleScope.launch {
            val r = app.diagnoser.runAndReport(p, playingHere = false)
            show(r.verdict.tvMessage + "\n(Press OK to retry)", 0, isError = true)
        }
    }

    private fun show(text: String, ms: Long, isError: Boolean = false) {
        overlay.text = text
        overlay.tag = if (isError) "error" else null
        overlay.visibility = TextView.VISIBLE
        hideOverlay?.cancel()
        if (ms > 0) hideOverlay = lifecycleScope.launch { delay(ms); overlay.visibility = TextView.GONE }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (channels.isNotEmpty() && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (overlay.tag == "error") playChannel() else show(title, 3000)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        playChannel()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.mediaItemCount > 0) player.play()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        PlaybackStatus.stopped()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_INDEX = "index"
    }
}
