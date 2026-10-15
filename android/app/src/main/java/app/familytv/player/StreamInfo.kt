package app.familytv.player

import java.util.Locale

/**
 * A snapshot of what is really playing, taken from the formats the decoders were given and from
 * live counters. Every field is optional: live IPTV often declares nothing (no bitrate, no frame
 * rate), so measured values sit next to declared ones and unknowns are simply left out.
 */
data class StreamStats(
    // Video
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val width: Int = -1,
    val height: Int = -1,
    val declaredFps: Float = -1f,
    val measuredFps: Float = -1f,
    val videoBitrate: Int = -1,
    val colorTransfer: Int = -1,
    val videoDecoder: String? = null,
    val droppedFrames: Int = 0,
    val renderedFrames: Int = 0,
    // Audio
    val audioMime: String? = null,
    val audioCodecs: String? = null,
    val channels: Int = -1,
    val sampleRate: Int = -1,
    val audioBitrate: Int = -1,
    val audioLanguage: String? = null,
    val audioDecoder: String? = null,
    // Stream
    val url: String? = null,
    val isHls: Boolean = false,
    val containerMime: String? = null,
    /** From finished HLS segments: bytes over media duration, i.e. the stream's real bitrate. */
    val segmentBitrate: Long = -1,
    /** Bytes arriving per second over the last few seconds (live: about the stream bitrate). */
    val dataRate: Long = -1,
    /** ExoPlayer's network throughput estimate. */
    val bandwidth: Long = -1,
    val bufferedMs: Long = -1,
    val captions: String? = null,
)

data class InfoSection(val title: String, val rows: List<Pair<String, String>>)

object StreamInfo {
    // Values of androidx.media3.common.C.COLOR_TRANSFER_*; repeated so this stays a pure function.
    private const val TRANSFER_SDR = 3
    private const val TRANSFER_ST2084 = 6
    private const val TRANSFER_HLG = 7

    /** "HDR10", "HLG", "Dolby Vision", "SDR", or null when the stream doesn't say. */
    fun dynamicRange(mime: String?, codecs: String?, colorTransfer: Int): String? {
        val c = codecs?.lowercase(Locale.ROOT).orEmpty()
        if (mime == "video/dolby-vision" || c.startsWith("dvh") || c.startsWith("dva")) return "Dolby Vision"
        return when (colorTransfer) {
            TRANSFER_ST2084 -> "HDR10 (PQ)"
            TRANSFER_HLG -> "HLG"
            TRANSFER_SDR -> "SDR"
            else -> null
        }
    }

    /** "HLS · MPEG-TS segments", "MPEG-TS", "MP4", "Matroska (MKV)" … from the extractor's container type, else the URL. */
    fun container(containerMime: String?, url: String?, isHls: Boolean): String? {
        val byMime = when (containerMime?.lowercase(Locale.ROOT)) {
            "video/mp2t" -> "MPEG-TS"
            "video/mp4", "audio/mp4", "application/mp4" -> "MP4"
            "video/x-matroska", "audio/x-matroska" -> "Matroska (MKV)"
            "video/webm", "audio/webm" -> "WebM"
            "video/x-flv" -> "FLV"
            "video/avi", "video/x-msvideo" -> "AVI"
            "video/mp2p" -> "MPEG-PS"
            "audio/mpeg" -> "MP3"
            "audio/aac" -> "AAC (ADTS)"
            else -> null
        }
        if (isHls) return if (byMime != null) "HLS · $byMime segments" else "HLS"
        if (byMime != null) return byMime
        val ext = url?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return when (ext) {
            "m3u8" -> "HLS"
            "ts" -> "MPEG-TS"
            "mp4", "m4v" -> "MP4"
            "mkv" -> "Matroska (MKV)"
            "avi" -> "AVI"
            "webm" -> "WebM"
            null, "" -> null
            else -> ext.uppercase(Locale.ROOT)
        }
    }

    fun sections(s: StreamStats): List<InfoSection> {
        val video = buildList {
            TrackLabels.codec(s.videoMime, s.videoCodecs)?.let { add("Codec" to it) }
            TrackLabels.resolution(s.width, s.height)?.let { r -> add("Resolution" to (TrackLabels.quality(s.width, s.height)?.let { "$r ($it)" } ?: r)) }
            val declared = TrackLabels.frameRate(s.declaredFps)
            val measured = TrackLabels.frameRate(s.measuredFps)
            when {
                declared != null && measured != null -> add("Frame rate" to "$declared (playing at $measured)")
                declared != null -> add("Frame rate" to declared)
                measured != null -> add("Frame rate" to "$measured (measured)")
            }
            TrackLabels.bitrate(s.videoBitrate.toLong())?.let { add("Bitrate (declared)" to it) }
            dynamicRange(s.videoMime, s.videoCodecs, s.colorTransfer)?.let { add("Dynamic range" to it) }
            s.videoDecoder?.let { add("Decoder" to it) }
            if (s.renderedFrames > 0 || s.droppedFrames > 0) add("Dropped frames" to "${s.droppedFrames} of ${s.renderedFrames + s.droppedFrames}")
        }
        val audio = buildList {
            TrackLabels.codec(s.audioMime, s.audioCodecs)?.let { add("Codec" to it) }
            TrackLabels.channels(s.channels)?.let { add("Channels" to it) }
            TrackLabels.sampleRate(s.sampleRate)?.let { add("Sample rate" to it) }
            TrackLabels.bitrate(s.audioBitrate.toLong())?.let { add("Bitrate" to it) }
            TrackLabels.language(s.audioLanguage)?.let { add("Language" to it) }
            s.audioDecoder?.let { add("Decoder" to it) }
        }
        val stream = buildList {
            container(s.containerMime, s.url, s.isHls)?.let { add("Type" to it) }
            TrackLabels.bitrate(s.segmentBitrate)?.let { add("Measured bitrate" to it) }
            TrackLabels.bitrate(s.dataRate)?.let { add("Data received" to "$it (last 10 s)") }
            TrackLabels.bitrate(s.bandwidth)?.let { add("Connection speed" to it) }
            if (s.bufferedMs >= 0) add("Buffered" to String.format(Locale.US, "%.1f s", s.bufferedMs / 1000.0))
            s.captions?.let { add("Captions" to it) }
        }
        return listOf(InfoSection("Video", video), InfoSection("Audio", audio), InfoSection("Stream", stream)).filter { it.rows.isNotEmpty() }
    }

    /** One line for the menu: "1080p · H.264 · 5.1". */
    fun summary(s: StreamStats): String? =
        listOfNotNull(TrackLabels.quality(s.width, s.height), TrackLabels.codec(s.videoMime, s.videoCodecs), TrackLabels.channels(s.channels))
            .joinToString(" · ").ifEmpty { null }
}
