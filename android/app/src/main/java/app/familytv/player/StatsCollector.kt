package app.familytv.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.familytv.sync.PlaybackStatus

/**
 * Measures what the stream really delivers, for the Info panel: bytes arriving over the network
 * (a [TransferListener] on the data source), HLS segment bitrates (bytes over media duration),
 * the bandwidth estimate, decoder names and the frame rate actually rendered.
 */
@UnstableApi
class StatsCollector : AnalyticsListener, TransferListener {
    private val lock = Any()
    // (elapsedRealtime ms, bytes) per arrival, trimmed to the window.
    private val arrivals = ArrayDeque<Pair<Long, Long>>()
    private var since = SystemClock.elapsedRealtime()
    private val segments = ArrayDeque<Pair<Long, Long>>() // (bytes, media ms)

    @Volatile private var bandwidth = -1L
    @Volatile private var videoDecoder: String? = null
    @Volatile private var audioDecoder: String? = null
    private var lastRendered = -1
    private var lastRenderedAt = 0L
    private var fps = -1f

    fun reset() {
        synchronized(lock) {
            arrivals.clear(); segments.clear(); since = SystemClock.elapsedRealtime()
        }
        lastRendered = -1; fps = -1f
    }

    // --- TransferListener (loader threads) ---
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val last = arrivals.lastOrNull()
            // Bucket into 250 ms slots so the deque stays small at high bitrates.
            if (last != null && now - last.first < 250) arrivals[arrivals.size - 1] = last.first to last.second + bytesTransferred
            else arrivals.addLast(now to bytesTransferred.toLong())
            while (arrivals.isNotEmpty() && now - arrivals.first().first > WINDOW_MS) arrivals.removeFirst()
        }
    }

    // --- AnalyticsListener (playback thread) ---
    override fun onBandwidthEstimate(t: AnalyticsListener.EventTime, loadMs: Int, bytes: Long, bitrate: Long) {
        bandwidth = bitrate
        PlaybackStatus.bitrateKbps = (bitrate / 1000).toInt()
    }

    override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
        PlaybackStatus.droppedFrames += dropped
    }

    override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
    }

    override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
        if (data.dataType != C.DATA_TYPE_MEDIA) return
        if (data.trackType != C.TRACK_TYPE_DEFAULT && data.trackType != C.TRACK_TYPE_VIDEO) return
        if (data.mediaStartTimeMs == C.TIME_UNSET || data.mediaEndTimeMs == C.TIME_UNSET) return
        val ms = data.mediaEndTimeMs - data.mediaStartTimeMs
        if (ms <= 0 || info.bytesLoaded <= 0) return
        synchronized(lock) {
            segments.addLast(info.bytesLoaded to ms)
            while (segments.size > 6) segments.removeFirst()
        }
    }

    /** Reads the player on the main thread; call about once a second while the Info panel is open. */
    fun snapshot(player: ExoPlayer, url: String?, captions: String?): StreamStats {
        val v = player.videoFormat
        val a = player.audioFormat
        val counters = player.videoDecoderCounters
        val now = SystemClock.elapsedRealtime()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        if (lastRendered >= 0 && now > lastRenderedAt && rendered >= lastRendered) {
            val f = (rendered - lastRendered) * 1000f / (now - lastRenderedAt)
            // Smooth a little; ignore paused stretches.
            if (f > 1f) fps = if (fps < 0) f else fps * 0.6f + f * 0.4f
        }
        lastRendered = rendered; lastRenderedAt = now
        val (rate, segRate) = synchronized(lock) {
            val span = minOf(WINDOW_MS, now - since)
            val total = arrivals.sumOf { it.second }
            val r = if (span >= 2_000 && total > 0) total * 8_000 / span else -1L
            val segBytes = segments.sumOf { it.first }
            val segMs = segments.sumOf { it.second }
            r to if (segMs > 0) segBytes * 8_000 / segMs else -1L
        }
        return StreamStats(
            videoMime = v?.sampleMimeType,
            videoCodecs = v?.codecs,
            width = v?.width ?: -1,
            height = v?.height ?: -1,
            declaredFps = v?.frameRate ?: -1f,
            measuredFps = if (player.isPlaying) fps else -1f,
            videoBitrate = v?.bitrate ?: -1,
            colorTransfer = v?.colorInfo?.colorTransfer ?: -1,
            videoDecoder = videoDecoder,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            renderedFrames = rendered,
            audioMime = a?.sampleMimeType,
            audioCodecs = a?.codecs,
            channels = a?.channelCount ?: -1,
            sampleRate = a?.sampleRate ?: -1,
            audioBitrate = a?.bitrate ?: -1,
            audioLanguage = a?.language,
            audioDecoder = audioDecoder,
            url = url,
            isHls = player.currentManifest is HlsManifest,
            containerMime = v?.containerMimeType ?: a?.containerMimeType,
            segmentBitrate = segRate,
            dataRate = rate,
            bandwidth = bandwidth,
            bufferedMs = player.totalBufferedDuration,
            captions = captions,
        )
    }

    private companion object {
        const val WINDOW_MS = 10_000L
    }
}
