package app.familytv.player

import java.util.Locale

/**
 * Plain-words names for what a stream carries: languages, codecs, channel layouts, bitrates and
 * resolutions. Pure functions over the raw values media3 reports (-1 or null means unknown), so
 * the pickers and the Info panel stay consistent and the wording is unit tested.
 */
object TrackLabels {

    /** "English", "Español (México)"; null for no or undetermined language. */
    fun language(code: String?, display: Locale = Locale.getDefault()): String? {
        val tag = code?.trim()?.replace('_', '-')
        if (tag.isNullOrEmpty() || tag.equals("und", true) || tag.equals("unknown", true)) return null
        val loc = Locale.forLanguageTag(tag)
        val name = loc.getDisplayLanguage(display)
        // Locale echoes codes it doesn't know ("xx"); show those as-is, upper-cased.
        if (name.isBlank() || name.equals(loc.language, true)) return tag.uppercase(Locale.ROOT)
        val country = loc.getDisplayCountry(display)
        val base = name.replaceFirstChar { it.titlecase(display) }
        return if (country.isBlank()) base else "$base ($country)"
    }

    private val CODECS = listOf(
        "avc1" to "H.264", "avc3" to "H.264",
        "hvc1" to "H.265 (HEVC)", "hev1" to "H.265 (HEVC)",
        "dvh1" to "Dolby Vision", "dvhe" to "Dolby Vision", "dvav" to "Dolby Vision", "dva1" to "Dolby Vision",
        "av01" to "AV1", "vp09" to "VP9", "vp9" to "VP9", "vp8" to "VP8",
        "mp4a.40.2" to "AAC-LC", "mp4a.40.5" to "HE-AAC", "mp4a.40.29" to "HE-AAC v2", "mp4a.40.34" to "MP3",
        "mp4a.6b" to "MP3", "mp4a.69" to "MP3", "mp4a" to "AAC",
        "ac-3" to "Dolby Digital", "ec-3" to "Dolby Digital Plus", "ac-4" to "Dolby AC-4",
        "opus" to "Opus", "flac" to "FLAC", "dtsc" to "DTS", "dtsh" to "DTS-HD", "dtsl" to "DTS-HD MA",
    )

    private val MIMES = mapOf(
        "video/avc" to "H.264", "video/hevc" to "H.265 (HEVC)", "video/dolby-vision" to "Dolby Vision",
        "video/av01" to "AV1", "video/x-vnd.on2.vp9" to "VP9", "video/x-vnd.on2.vp8" to "VP8",
        "video/mpeg2" to "MPEG-2", "video/mp4v-es" to "MPEG-4 Part 2", "video/mjpeg" to "Motion JPEG",
        "audio/mp4a-latm" to "AAC", "audio/ac3" to "Dolby Digital", "audio/eac3" to "Dolby Digital Plus",
        "audio/eac3-joc" to "Dolby Atmos (DD+)", "audio/ac4" to "Dolby AC-4", "audio/true-hd" to "Dolby TrueHD",
        "audio/vnd.dts" to "DTS", "audio/vnd.dts.hd" to "DTS-HD", "audio/vnd.dts.hd;profile=lbr" to "DTS Express",
        "audio/mpeg" to "MP3", "audio/mpeg-l1" to "MPEG Layer I", "audio/mpeg-l2" to "MPEG Layer II",
        "audio/opus" to "Opus", "audio/flac" to "FLAC", "audio/vorbis" to "Vorbis", "audio/raw" to "PCM",
        "application/cea-608" to "CEA-608", "application/cea-708" to "CEA-708",
        "application/x-subrip" to "SRT", "text/vtt" to "WebVTT", "application/ttml+xml" to "TTML",
        "text/x-ssa" to "SSA/ASS", "application/dvbsubs" to "DVB", "application/pgs" to "PGS",
        "application/vobsub" to "VobSub", "application/x-mp4-vtt" to "WebVTT", "application/x-quicktime-tx3g" to "TX3G",
    )

    /** Codec name from the RFC 6381 codecs string when present (more specific), else from the MIME type. */
    fun codec(mime: String?, codecs: String?): String? {
        // Subtitles parsed while extracting arrive as media3's own cue format with the original type in codecs.
        if (mime.equals(MEDIA3_CUES, true)) return codecs?.lowercase(Locale.ROOT)?.let { MIMES[it] }
        val c = codecs?.lowercase(Locale.ROOT)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (c != null) CODECS.firstOrNull { (prefix, _) -> c == prefix || c.startsWith("$prefix.") }?.let { return it.second }
        val m = mime?.lowercase(Locale.ROOT) ?: return c?.uppercase(Locale.ROOT)
        return MIMES[m] ?: c?.uppercase(Locale.ROOT) ?: m.substringAfter('/').uppercase(Locale.ROOT)
    }

    fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 3 -> "2.1"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count channels"
    }

    /** "5.2 Mbps", "128 kbps"; null when unknown. */
    fun bitrate(bitsPerSecond: Long): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        else -> "${(bitsPerSecond + 500) / 1000} kbps"
    }

    fun sampleRate(hz: Int): String? = when {
        hz <= 0 -> null
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> String.format(Locale.US, "%.1f kHz", hz / 1000.0)
    }

    fun frameRate(fps: Float): String? = when {
        fps <= 0f || fps.isNaN() -> null
        kotlin.math.abs(fps - Math.round(fps)) < 0.01f -> "${Math.round(fps)} fps"
        else -> String.format(Locale.US, "%.2f fps", fps)
    }

    fun resolution(width: Int, height: Int): String? = if (width > 0 && height > 0) "$width×$height" else null

    /** Everyday quality name from the picture height: 4K, 1080p, 720p, … */
    fun quality(width: Int, height: Int): String? {
        // Letterboxed films (1920×800) are still "1080p", so look at the width too.
        val h = if (width > 0 && height > 0) maxOf(height, width * 9 / 16) else height
        return when {
            h <= 0 -> null
            h >= 2000 -> "4K"
            h >= 1400 -> "1440p"
            h >= 1000 -> "1080p"
            h >= 700 -> "720p"
            h >= 560 -> "576p"
            h >= 470 -> "480p"
            else -> "${height}p"
        }
    }

    /** "English · Dolby Digital · 5.1" — or the stream's own label ("Commentary") in front when it has one. */
    fun audio(language: String?, label: String?, mime: String?, codecs: String?, channels: Int, index: Int): String {
        val parts = listOfNotNull(
            label?.trim()?.takeIf { it.isNotEmpty() } ?: language(language),
            codec(mime, codecs),
            channels(channels),
        ).distinct()
        return parts.joinToString(" · ").ifEmpty { "Audio ${index + 1}" }
    }

    /** "1080p · 1920×1080 · 5.2 Mbps". */
    fun video(width: Int, height: Int, bitrate: Int, frameRate: Float, mime: String?, codecs: String?, index: Int): String {
        val parts = listOfNotNull(quality(width, height), resolution(width, height), frameRate(frameRate)?.takeIf { frameRate > 31f }, bitrate(bitrate.toLong()))
        return (if (parts.isEmpty()) listOfNotNull(codec(mime, codecs)) else parts).joinToString(" · ").ifEmpty { "Video ${index + 1}" }
    }

    /** "English · SRT"; embedded broadcast captions without a language read "Closed captions (CC1)". */
    fun text(language: String?, label: String?, mime: String?, codecs: String?, accessibilityChannel: Int, index: Int): String {
        val lang = label?.trim()?.takeIf { it.isNotEmpty() } ?: language(language)
        val m = (if (mime.equals(MEDIA3_CUES, true)) codecs else mime)?.lowercase(Locale.ROOT)
        val isCc = m == "application/cea-608" || m == "application/cea-708"
        if (isCc && lang == null) return if (accessibilityChannel > 0) "Closed captions (CC$accessibilityChannel)" else "Closed captions"
        return listOfNotNull(lang ?: "Subtitles ${index + 1}", codec(m, null)).distinct().joinToString(" · ")
    }

    private const val MEDIA3_CUES = "application/x-media3-cues"
}
