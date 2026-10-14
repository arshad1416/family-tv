package app.familytv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import app.familytv.data.NowNext
import app.familytv.iptv.Streams
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen player. Live TV: D-pad up/down (or channel keys) zaps through the category, each
 * tune shows an info banner, and OK opens a mini guide on the left (Back closes it).
 * When a stream fails it falls back from .ts to .m3u8 once, then runs a network check and
 * shows the person a plain-language reason instead of a spinner.
 *
 * Live keys are handled in onKeyDown/onKeyUp. That only works if no view consumes the D-pad
 * first, so in live mode PlayerView and the overlay are kept out of focus unless the mini guide
 * is open.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private var hideOverlay: Job? = null

    private var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
    private var index = 0
    private var triedHls = false
    private var profile: Profile? = null
    private var title = ""
    private var tunedAt = 0L
    private var retries = 0

    // Live overlay state, drawn by LiveOverlay in a ComposeView.
    private var overlayView: ComposeView? = null
    private var playing by mutableIntStateOf(0)
    private var banner by mutableStateOf<Int?>(null)
    private var guideOpen by mutableStateOf(false)
    private var programmes by mutableStateOf<Map<String, List<NowNext>>>(emptyMap())
    private var programmesAt = 0L
    private var bannerJob: Job? = null
    private var guideJob: Job? = null
    private var okPending = false
    private val closeGuideOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeGuide()
    }

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
            view.isFocusable = false
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            overlayView = ComposeView(this).apply {
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setContent {
                    FamilyTvTheme {
                        LiveOverlay(channels, playing, banner, guideOpen, programmes, onTune = ::tuneFromGuide, onFocusChannel = { if (guideOpen) banner = it })
                    }
                }
            }
            root.addView(overlayView, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) // under the error text
            onBackPressedDispatcher.addCallback(this, closeGuideOnBack)
            lifecycleScope.launch {
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: return@launch finish()
                val cat = intent.getStringExtra(EXTRA_CATEGORY) ?: return@launch finish()
                channels = app.db.catalog().channels(src, cat).first()
                val stream = intent.getStringExtra(EXTRA_STREAM)
                index = channels.indexOfFirst { it.streamId == stream }.takeIf { it >= 0 }
                    ?: intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
                playChannel()
            }
        }
    }

    private var tune: Job? = null

    /**
     * Shows the channel name at once, stops the old stream so its connection is closed (many lines
     * allow only one), and waits briefly so holding the button flips through channels without
     * opening a stream for each one.
     */
    private fun playChannel(delayMs: Long = 0) {
        val ch = channels.getOrNull(index) ?: return
        val p = profile ?: return
        triedHls = false
        retries = 0
        tunedAt = System.currentTimeMillis()
        title = "${ch.num}  ${ch.name}"
        playing = index
        overlay.visibility = TextView.GONE
        overlay.tag = null
        showBanner(index)
        player.stop()
        tune?.cancel()
        tune = lifecycleScope.launch {
            delay(delayMs)
            val url = Streams(app.db, p).live(ch) ?: return@launch show("$title\nThis channel has no stream address.", 4000, isError = true)
            start(url, title)
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
        // One-connection lines can refuse a new stream for a moment after the old one closes:
        // straight after a tune, try the same channel again (twice, backing off) before anything else.
        if (ch != null && p != null && retries < 2 && System.currentTimeMillis() - tunedAt < 15_000) {
            retries++
            val attempt = index
            lifecycleScope.launch {
                delay(1_500L * retries)
                if (attempt == index) Streams(app.db, p).live(ch, if (triedHls) "m3u8" else "ts")?.let { start(it, title) }
            }
            return
        }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty() || keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // With the mini guide open, Compose moves focus through the list; whatever reaches here
        // fell off its edge and must not zap.
        if (guideOpen) return keyCode in LIVE_KEYS || super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(1)
            in OK_KEYS -> if (event.repeatCount == 0) {
                if (overlay.tag == "error") playChannel() else okPending = true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // The guide opens on key-up, so the release of the same OK press can't also click a row in it.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isNotEmpty() && keyCode in OK_KEYS) {
            if (okPending && !guideOpen) openGuide()
            okPending = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (guideOpen) closeGuideLater()
    }

    private fun showBanner(i: Int) {
        banner = i
        refreshProgrammes()
        bannerJob?.cancel()
        if (!guideOpen) bannerJob = lifecycleScope.launch { delay(4_000); banner = null }
    }

    private fun openGuide() {
        guideOpen = true
        closeGuideOnBack.isEnabled = true
        overlayView?.apply { descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; requestFocus() }
        showBanner(index)
        closeGuideLater()
    }

    private fun closeGuide() {
        guideOpen = false
        closeGuideOnBack.isEnabled = false
        guideJob?.cancel()
        // Block first: clearing focus otherwise hands it straight back to the first focusable view.
        overlayView?.apply { descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; clearFocus() }
        showBanner(index)
    }

    private fun closeGuideLater() {
        guideJob?.cancel()
        guideJob = lifecycleScope.launch { delay(20_000); closeGuide() }
    }

    private fun tuneFromGuide(i: Int) {
        val change = i != index
        index = i
        closeGuide()
        if (change) playChannel()
    }

    /** Now/next for the whole category, reused by the banner and the mini guide for a few minutes. */
    private fun refreshProgrammes() {
        val now = System.currentTimeMillis()
        if (now - programmesAt < 5 * 60_000) return
        programmesAt = now
        val ids = channels.mapNotNull { it.epgId }.distinct()
        lifecycleScope.launch {
            runCatching { ids.chunked(500).flatMap { app.db.catalog().programmes(it, now, now + 3 * 3_600_000L) }.groupBy { it.epgId } }
                .onSuccess { programmes = it }
                .onFailure { programmesAt = 0 }
        }
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        playChannel(delayMs = 600)
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
        /** Optional: the channel to start on, when the caller doesn't know its index in the category. */
        const val EXTRA_STREAM = "stream"

        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private val LIVE_KEYS = OK_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        )
    }
}
