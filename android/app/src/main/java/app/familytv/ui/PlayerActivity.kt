package app.familytv.ui

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.core.Http
import app.familytv.data.ChannelEntity
import app.familytv.data.NowNext
import app.familytv.iptv.Streams
import app.familytv.player.CaptionPrefs
import app.familytv.player.ONLINE_SUFFIX
import app.familytv.player.OnlineSubtitles
import app.familytv.player.PlayerTracks
import app.familytv.player.StatsCollector
import app.familytv.player.StreamStats
import app.familytv.player.TrackLabels
import app.familytv.portal.Profile
import app.familytv.subs.MediaRef
import app.familytv.subs.OpenSubtitles
import app.familytv.subs.SubtitleHit
import app.familytv.subs.SubtitleRequest
import app.familytv.subs.SubtitleSearch
import app.familytv.sync.PlaybackStatus
import app.familytv.trakt.PlayItem
import app.familytv.user.ProgressKey
import app.familytv.trakt.PlaybackTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Full-screen player. Live TV: D-pad up/down (or channel keys) zaps through the category, each
 * tune shows an info banner, OK opens a mini guide on the left (Back closes it) and right opens
 * the options panel (captions, audio & video, stream info).
 * When a stream fails it falls back from .ts to .m3u8 once, then runs a network check and
 * shows the person a plain-language reason instead of a spinner.
 *
 * Live keys are handled in onKeyDown/onKeyUp. That only works if no view consumes the D-pad
 * first, so in live mode PlayerView and the overlay are kept out of focus unless the mini guide
 * or a panel is open. Movies and episodes use PlayerView's own controls, with our captions,
 * tracks and info buttons above them (D-pad up from the controls reaches them on TV).
 * The remote's Info, Captions and Menu keys work in both modes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private lateinit var tracks: PlayerTracks
    private lateinit var captionPrefs: CaptionPrefs
    private val stats = StatsCollector()
    private var hideOverlay: Job? = null

    private var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
    private var index = 0
    private var triedHls = false
    private var profile: Profile? = null
    private var title = ""
    private var tunedAt = 0L
    private var retries = 0
    /** Movies and episodes: resume point and Trakt scrobbles. */
    private var tracker: PlaybackTracker? = null
    private var live = false
    private var url: String? = null

    // Overlay state, drawn by LiveOverlay / PlayerButtons / PlayerPanels in a ComposeView.
    private var overlayView: ComposeView? = null
    private var playing by mutableIntStateOf(0)
    private var banner by mutableStateOf<Int?>(null)
    private var guideOpen by mutableStateOf(false)
    /** TV: the full guide that Back opens over live TV (see [PlayerGuide]). */
    private var bigGuide by mutableStateOf(GuideMode.Off)
    private var guideWindow: android.graphics.Rect? = null
    private var programmes by mutableStateOf<Map<String, List<NowNext>>>(emptyMap())
    private var programmesAt = 0L
    private var bannerJob: Job? = null
    private var guideJob: Job? = null
    private var okPending = false

    // Captions / tracks / info.
    private var panel by mutableStateOf<PlayerPanel?>(null)
    private var vodControls by mutableStateOf(false)
    private var buttonsActive by mutableStateOf(false)
    private val buttonsFocus = FocusRequester()
    private var playItem: PlayItem? = null
    private var media: MediaRef? = null
    private var online: OnlineSubtitles? = null

    // Phones and tablets: tap the video for on-screen controls, since there's no remote.
    private var touch = false
    private var controls by mutableStateOf(false)
    private var controlsJob: Job? = null
    /** TV live: Back opens the guide over the video, then shrinks the video, then leaves. */
    private val guideBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = when (bigGuide) {
            GuideMode.Off -> openBigGuide()
            GuideMode.Over -> showBigGuide(GuideMode.Picture)
            GuideMode.Picture -> finish()
        }
    }
    private val closeGuideOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeGuide()
    }
    private val panelBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = if (panel != null) closePanel() else leaveButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        profile = app.portal.cachedConfig?.profile
        val form = formFactor()
        touch = form.touch

        // Bigger buffer than the default for live IPTV: survives short Wi-Fi dips on sticks.
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000).build()
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT).setTransferListener(stats)
        // DefaultDataSource adds file:// for downloaded subtitles; network still goes through OkHttp.
        val dataSource = DefaultDataSource.Factory(this, http)
        // Live .ts streams rarely declare their captions, so always look for CEA-608 in the video (HLS does this by default).
        val extractors = DefaultExtractorsFactory()
            .setTsSubtitleFormats(listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()))
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .build()
        captionPrefs = CaptionPrefs(this)
        tracks = PlayerTracks(player, captionPrefs)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) PlaybackStatus.bufferingEvents++
                if (state == Player.STATE_READY) overlay.takeIf { it.tag == "error" }?.let { it.visibility = TextView.GONE; it.tag = null }
            }
            override fun onTracksChanged(t: Tracks) = tracks.update(t)
            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isNotEmpty() && !tracks.cuesSeen) tracks.cuesSeen = true
            }
        })
        player.addAnalyticsListener(stats)

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

        val vodUrl = intent.getStringExtra(EXTRA_URL)
        live = vodUrl == null
        overlayView = ComposeView(this).apply {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                FamilyTvTheme {
                    CompositionLocalProvider(LocalFormFactor provides form) { Overlay() }
                }
            }
        }
        root.addView(overlayView, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) // under the error text
        // Later callbacks run first: the panels, then the mini guide, then the big guide.
        onBackPressedDispatcher.addCallback(this, guideBack)
        onBackPressedDispatcher.addCallback(this, closeGuideOnBack)
        onBackPressedDispatcher.addCallback(this, panelBack) // added last, so it runs first

        if (vodUrl != null) {
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            playItem = PlayItem.from(intent)
            online = onlineSubtitles()
            view.useController = true
            view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vodControls = it == View.VISIBLE })
            start(vodUrl, title)
            tracker = PlaybackTracker.attach(this, intent, player)
        } else {
            view.useController = false
            view.isFocusable = false
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            lifecycleScope.launch {
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: return@launch finish()
                val cat = intent.getStringExtra(EXTRA_CATEGORY) ?: return@launch finish()
                channels = app.db.catalog().channels(src, cat).first()
                guideBack.isEnabled = !touch && channels.isNotEmpty()
                val stream = intent.getStringExtra(EXTRA_STREAM)
                index = channels.indexOfFirst { it.streamId == stream }.takeIf { it >= 0 }
                    ?: intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
                playChannel()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize()) {
            if (live) {
                val touchUi = if (!touch) null else TouchControls(
                    visible = controls,
                    onTap = ::onTap,
                    onZap = { zap(it); showControls() },
                    onGuide = { hideControls(); openGuide() },
                    onClose = ::finish,
                    captionsOn = tracks.captionsOn,
                    onCaptions = { openPanel(PlayerPanel.Captions) },
                    onTracks = if (tracks.hasChoices) ({ openPanel(PlayerPanel.Tracks) }) else null,
                    onInfo = { openPanel(PlayerPanel.Info) },
                )
                // The guide fades in over the video and out again; the last mode stays drawn while it fades.
                val shownMode = remember { mutableStateOf(GuideMode.Over) }
                if (bigGuide != GuideMode.Off) shownMode.value = bigGuide
                AnimatedVisibility(bigGuide != GuideMode.Off, enter = fadeIn(tween(VIDEO_MOVE_MS.toInt())), exit = fadeOut(tween(VIDEO_MOVE_MS.toInt()))) {
                    PlayerGuide(shownMode.value, channels, playing, rememberFavouriteKeys(), onPick = ::pickFromBigGuide, onWindow = ::onGuideWindow)
                }
                if (bigGuide == GuideMode.Off) LiveOverlay(channels, playing, banner, guideOpen, programmes, onTune = ::tuneFromGuide, onFocusChannel = { if (guideOpen) banner = it }, touch = touchUi)
            } else if ((vodControls || buttonsActive) && panel == null) {
                PlayerButtons(
                    tracks, buttonsFocus,
                    onCaptions = { openPanel(PlayerPanel.Captions) },
                    onTracks = { openPanel(PlayerPanel.Tracks) },
                    onInfo = { openPanel(PlayerPanel.Info) },
                    onLeave = ::leaveButtons,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(24.dp),
                )
                if (buttonsActive) LaunchedEffect(Unit) {
                    repeat(5) { withFrameNanos { }; if (runCatching { buttonsFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
                }
            }
            PlayerPanels(panel, tracks, online, live, touch, stats = ::snapshot, onOpen = ::openPanel, onClose = ::closePanel)
        }
    }

    private fun snapshot(): StreamStats = stats.snapshot(player, url, tracks.captionSummary())

    // ---------- captions, tracks, info ----------

    private fun onlineSubtitles(): OnlineSubtitles? {
        val key = app.portal.cachedConfig?.subtitlesApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (playItem == null) return null
        val client = OpenSubtitles(key, "FamilyTV v${BuildConfig.VERSION_NAME}", Http.client)
        return OnlineSubtitles(client, lifecycleScope, File(cacheDir, "subtitles"), ::subtitleRequest, ::sideLoad)
    }

    private suspend fun subtitleRequest(): SubtitleRequest {
        val ref = media ?: resolveMedia(playItem!!).also { media = it }
        val langs = listOfNotNull(
            captionPrefs.language?.let { SubtitleSearch.languageCode(Locale.forLanguageTag(it)) },
            SubtitleSearch.languageCode(Locale.getDefault()),
            "en",
        ).distinct()
        return ref.request(langs)
    }

    /**
     * What subtitle matching needs, from the catalog row behind the [PlayItem] (the tmdb/year the
     * provider's list carries), falling back to get_vod_info / get_series_info for missing ids.
     * Runs only when the viewer searches online.
     */
    private suspend fun resolveMedia(item: PlayItem): MediaRef {
        val dao = app.db.catalog()
        val episode = item.kind != ProgressKey.MOVIE
        val (tmdb, year) = runCatching {
            if (episode) dao.seriesItem(item.sourceId, item.itemId)?.let { it.tmdb to it.year }
            else dao.movie(item.sourceId, item.itemId)?.let { it.tmdb to it.year }
        }.getOrNull() ?: (null to null)
        var ref = MediaRef(
            episode = episode, title = item.name, year = year, tmdbId = tmdb,
            season = item.season.takeIf { episode }, episodeNumber = item.episode.takeIf { episode },
        )
        val src = profile?.sources?.firstOrNull { it.id == item.sourceId && it.type == "xtream" }
        if (!ref.hasIds && src != null) {
            runCatching { app.catalog.infoIds(src.id, item.itemId, src, series = episode) }.getOrNull()?.let { ids ->
                ref = ref.copy(tmdbId = ids.tmdb, imdbId = ids.imdb, year = ref.year ?: ids.year)
            }
        }
        return ref
    }

    /** Adds the downloaded .srt to the playing item (keeping the position) and selects it. */
    private fun sideLoad(file: File, hit: SubtitleHit) {
        val item = player.currentMediaItem ?: return
        val id = "online-${hit.fileId}"
        val lang = TrackLabels.language(hit.language) ?: hit.language
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(hit.language)
            .setLabel("$lang $ONLINE_SUFFIX")
            .setId(id)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val position = player.currentPosition
        player.setMediaItem(item.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), position)
        player.prepare()
        tracks.expectText(id)
        closePanel()
        show("Subtitles: $lang", 2_500)
    }

    private fun togglePanel(p: PlayerPanel) = if (panel == p) closePanel() else openPanel(p)

    private fun openPanel(p: PlayerPanel) {
        if (guideOpen && p != PlayerPanel.Info) closeGuide()
        hideControls()
        buttonsActive = false
        panel = p
        panelBack.isEnabled = true
        syncOverlayFocus()
    }

    private fun closePanel() {
        panel = null
        panelBack.isEnabled = buttonsActive
        syncOverlayFocus()
        if (!live && !touch) view.showController()
    }

    private fun focusButtons() {
        buttonsActive = true
        panelBack.isEnabled = true
        syncOverlayFocus()
    }

    private fun leaveButtons() {
        buttonsActive = false
        panelBack.isEnabled = panel != null
        syncOverlayFocus()
        view.showController()
    }

    /**
     * The overlay takes D-pad focus only while something in it needs the keys (the guide, a list
     * panel, the movie buttons). Info stays passive so zapping and seeking keep working.
     */
    private fun syncOverlayFocus() {
        val v = overlayView ?: return
        val want = guideOpen || bigGuide != GuideMode.Off || buttonsActive || (panel != null && panel != PlayerPanel.Info)
        if (want) {
            v.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            v.requestFocus()
        } else {
            // Block first: clearing focus otherwise hands it straight back to the first focusable view.
            v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            v.clearFocus()
            if (!live) view.requestFocus()
        }
    }

    private fun toggleCaptions() {
        tracks.toggleCaptions()
        show(if (tracks.captionsOn) "Captions on" else "Captions off", 2_000)
    }

    /** Info, Captions, Menu and Audio-track keys; neither PlayerView nor the panels use them, so they arrive here. */
    private fun panelKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in PANEL_KEYS) return false
        if (event.repeatCount == 0) when (keyCode) {
            KeyEvent.KEYCODE_INFO -> togglePanel(PlayerPanel.Info)
            KeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
            KeyEvent.KEYCODE_MENU -> togglePanel(PlayerPanel.Menu)
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> togglePanel(PlayerPanel.Tracks)
        }
        return true
    }

    /**
     * Movies on TV: an up press the player controls didn't use (nothing above the focused control)
     * reaches the activity before focus search would; it moves to our buttons instead.
     */
    private fun upFromControls(keyCode: Int): Boolean {
        if (live || touch || keyCode != KeyEvent.KEYCODE_DPAD_UP || panel != null || buttonsActive || !view.isControllerFullyVisible) return false
        val f = currentFocus ?: return false
        if (!f.isInside(view)) return false
        val next = f.focusSearch(View.FOCUS_UP)
        if (next != null && next.isInside(view)) return false
        focusButtons()
        return true
    }

    private fun View.isInside(parent: View): Boolean {
        var v: View? = this
        while (v != null) { if (v === parent) return true; v = v.parent as? View }
        return false
    }

    // ---------- live TV ----------

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
        this.url = url
        stats.reset()
        tracks.onNewItem()
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
            show(r.verdict.tvMessage + if (touch) "\n(Tap to retry)" else "\n(Press OK to retry)", 0, isError = true)
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
        if (panelKey(keyCode, event) || upFromControls(keyCode)) return true
        if (channels.isEmpty() || keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // With the mini guide or a list panel open, Compose moves focus through the list; whatever
        // reaches here fell off its edge and must not zap.
        if (guideOpen || bigGuide != GuideMode.Off || (panel != null && panel != PlayerPanel.Info)) return keyCode in LIVE_KEYS || super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) openPanel(PlayerPanel.Menu)
            in OK_KEYS -> if (event.repeatCount == 0) {
                if (overlay.tag == "error") playChannel() else okPending = true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // The guide opens on key-up, so the release of the same OK press can't also click a row in it.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in PANEL_KEYS) return true
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
        if (bigGuide == GuideMode.Over) closeBigGuideLater()
    }

    private fun onTap() {
        when {
            panel != null -> closePanel()
            guideOpen -> closeGuide()
            overlay.tag == "error" -> playChannel()
            controls -> { hideControls(); banner = null }
            else -> { showControls(); showBanner(index) }
        }
    }

    private fun showControls() {
        controls = true
        controlsJob?.cancel()
        controlsJob = lifecycleScope.launch { delay(4_000); controls = false }
    }

    private fun hideControls() {
        controlsJob?.cancel()
        controls = false
    }

    private fun showBanner(i: Int) {
        banner = i
        refreshProgrammes()
        bannerJob?.cancel()
        if (!guideOpen) bannerJob = lifecycleScope.launch { delay(4_000); banner = null }
    }

    private fun openGuide() {
        if (panel != null && panel != PlayerPanel.Info) closePanel()
        guideOpen = true
        closeGuideOnBack.isEnabled = true
        syncOverlayFocus()
        showBanner(index)
        closeGuideLater()
    }

    private fun closeGuide() {
        guideOpen = false
        closeGuideOnBack.isEnabled = false
        guideJob?.cancel()
        syncOverlayFocus()
        showBanner(index)
    }

    private fun closeGuideLater() {
        guideJob?.cancel()
        guideJob = lifecycleScope.launch { delay(20_000); closeGuide() }
    }

    private var bigGuideJob: Job? = null

    private fun openBigGuide() {
        if (panel != null) closePanel()
        if (guideOpen) closeGuide()
        banner = null
        showBigGuide(GuideMode.Over)
        syncOverlayFocus()
    }

    /** Over the video it closes itself when left alone; with the small picture it stays until Back or OK. */
    private fun showBigGuide(mode: GuideMode) {
        bigGuide = mode
        bigGuideJob?.cancel()
        if (mode == GuideMode.Over) closeBigGuideLater()
        layoutVideo()
    }

    private fun closeBigGuide() {
        showBigGuide(GuideMode.Off)
        syncOverlayFocus()
        showBanner(index)
    }

    private fun closeBigGuideLater() {
        bigGuideJob?.cancel()
        bigGuideJob = lifecycleScope.launch { delay(30_000); closeBigGuide() }
    }

    /** OK in the big guide: another channel plays in the small picture; the one already playing goes full screen. */
    private fun pickFromBigGuide(i: Int) {
        if (i == index) return closeBigGuide()
        index = i
        playChannel()
        banner = null
        showBigGuide(GuideMode.Picture)
    }

    private fun onGuideWindow(r: androidx.compose.ui.geometry.Rect) {
        guideWindow = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        layoutVideo()
    }

    /**
     * Full screen, or shrunk into the big guide's window. Android 7+ moves and scales the video
     * surface in step with the view, so it glides there; Android 6 (oldest Fire TV sticks) can't
     * scale a SurfaceView, so the view is resized in one step instead.
     */
    private fun layoutVideo() {
        val w = guideWindow.takeIf { bigGuide == GuideMode.Picture }
        if (Build.VERSION.SDK_INT >= 24) {
            val full = w == null || view.width == 0 || view.height == 0
            view.pivotX = 0f
            view.pivotY = 0f
            view.animate()
                .scaleX(if (full) 1f else w!!.width().toFloat() / view.width)
                .scaleY(if (full) 1f else w!!.height().toFloat() / view.height)
                .translationX(if (full) 0f else w!!.left.toFloat())
                .translationY(if (full) 0f else w!!.top.toFloat())
                .setDuration(VIDEO_MOVE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            return
        }
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val want = if (w == null) FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        else FrameLayout.LayoutParams(w.width(), w.height()).apply { leftMargin = w.left; topMargin = w.top }
        if (lp.width == want.width && lp.height == want.height && lp.leftMargin == want.leftMargin && lp.topMargin == want.topMargin) return
        view.layoutParams = want
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
        tracker?.finish()
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

        /** How long the video takes to move into the guide's window and back; the guide's fades match it. */
        const val VIDEO_MOVE_MS = 300L

        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private val LIVE_KEYS = OK_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        )
        private val PANEL_KEYS = setOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
    }
}
