package app.familytv

import app.familytv.player.StreamInfo
import app.familytv.player.StreamStats
import app.familytv.player.TrackLabels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TrackLabelsTest {
    private val en = Locale.ENGLISH
    private val saved = Locale.getDefault()

    // Labels are in the device language; pin it so the expectations hold anywhere.
    @Before fun pinLocale() = Locale.setDefault(Locale.US)
    @After fun restoreLocale() = Locale.setDefault(saved)

    @Test
    fun languages() {
        assertEquals("English", TrackLabels.language("en", en))
        assertEquals("French (Canada)", TrackLabels.language("fr-CA", en))
        assertNull(TrackLabels.language("und", en))
        assertNull(TrackLabels.language(null, en))
        assertEquals("XX", TrackLabels.language("xx", en))
    }

    @Test
    fun codecsPreferTheCodecsStringOverTheMimeType() {
        assertEquals("H.264", TrackLabels.codec("video/avc", "avc1.640028"))
        assertEquals("H.265 (HEVC)", TrackLabels.codec(null, "hvc1.2.4.L153.B0"))
        assertEquals("HE-AAC", TrackLabels.codec("audio/mp4a-latm", "mp4a.40.5"))
        assertEquals("HE-AAC v2", TrackLabels.codec("audio/mp4a-latm", "mp4a.40.29"))
        assertEquals("AAC-LC", TrackLabels.codec("audio/mp4a-latm", "mp4a.40.2"))
        assertEquals("Dolby Digital Plus", TrackLabels.codec("audio/eac3", null))
        assertEquals("MPEG-2", TrackLabels.codec("video/mpeg2", null))
        assertEquals("CEA-608", TrackLabels.codec("application/cea-608", null))
        assertEquals("XYZ", TrackLabels.codec("video/xyz", null))
        assertNull(TrackLabels.codec(null, null))
    }

    @Test
    fun numbers() {
        assertEquals("5.1", TrackLabels.channels(6))
        assertEquals("Stereo", TrackLabels.channels(2))
        assertNull(TrackLabels.channels(-1))
        assertEquals("5.2 Mbps", TrackLabels.bitrate(5_200_000))
        assertEquals("128 kbps", TrackLabels.bitrate(128_000))
        assertNull(TrackLabels.bitrate(-1))
        assertEquals("48 kHz", TrackLabels.sampleRate(48_000))
        assertEquals("44.1 kHz", TrackLabels.sampleRate(44_100))
        assertEquals("29.97 fps", TrackLabels.frameRate(29.97f))
        assertEquals("50 fps", TrackLabels.frameRate(50f))
        assertNull(TrackLabels.frameRate(-1f))
    }

    @Test
    fun qualityNamesHandleLetterboxedFilms() {
        assertEquals("1080p", TrackLabels.quality(1920, 1080))
        assertEquals("1080p", TrackLabels.quality(1920, 800))
        assertEquals("4K", TrackLabels.quality(3840, 2160))
        assertEquals("720p", TrackLabels.quality(1280, 720))
        assertEquals("360p", TrackLabels.quality(640, 360))
        assertNull(TrackLabels.quality(-1, -1))
    }

    @Test
    fun trackLabels() {
        assertEquals("English · Dolby Digital · 5.1", TrackLabels.audio("en", null, "audio/ac3", null, 6, 0))
        assertEquals("Commentary · AAC · Stereo", TrackLabels.audio("en", "Commentary", "audio/mp4a-latm", null, 2, 1))
        assertEquals("Audio 3", TrackLabels.audio(null, null, null, null, -1, 2))
        assertEquals("1080p · 1920×1080 · 5.0 Mbps", TrackLabels.video(1920, 1080, 5_000_000, 25f, "video/avc", null, 0))
        assertEquals("720p · 1280×720 · 60 fps", TrackLabels.video(1280, 720, -1, 59.999f, null, null, 0))
        assertEquals("H.264", TrackLabels.video(-1, -1, -1, -1f, "video/avc", null, 0))
        assertEquals("Closed captions (CC1)", TrackLabels.text(null, null, "application/cea-608", null, 1, 0))
        assertEquals("Closed captions", TrackLabels.text(null, null, "application/cea-608", null, -1, 0))
        assertEquals("Spanish · SRT", TrackLabels.text("es", null, "application/x-subrip", null, -1, 0))
        assertEquals("Subtitles 2 · WebVTT", TrackLabels.text(null, null, "text/vtt", null, -1, 1))
        // Parsed during extraction: the original type rides in codecs.
        assertEquals("English (online) · SRT", TrackLabels.text("en", "English (online)", "application/x-media3-cues", "application/x-subrip", -1, 0))
        assertEquals("Closed captions (CC1)", TrackLabels.text(null, null, "application/x-media3-cues", "application/cea-608", 1, 0))
    }

    @Test
    fun streamInfoSections() {
        val s = StreamStats(
            videoMime = "video/hevc", videoCodecs = "hvc1.2.4.L150", width = 3840, height = 2160, declaredFps = 23.976f, measuredFps = 24f,
            videoBitrate = 15_000_000, colorTransfer = 6, droppedFrames = 2, renderedFrames = 998,
            audioMime = "audio/eac3", channels = 6, sampleRate = 48_000, audioLanguage = "en",
            url = "http://h/movie/u/p/1.mkv", containerMime = "video/x-matroska", dataRate = 16_000_000, bufferedMs = 12_300,
        )
        val sections = StreamInfo.sections(s).associate { it.title to it.rows.toMap() }
        assertEquals("3840×2160 (4K)", sections["Video"]!!["Resolution"])
        assertEquals("23.98 fps (playing at 24 fps)", sections["Video"]!!["Frame rate"])
        assertEquals("HDR10 (PQ)", sections["Video"]!!["Dynamic range"])
        assertEquals("2 of 1000", sections["Video"]!!["Dropped frames"])
        assertEquals("5.1", sections["Audio"]!!["Channels"])
        assertEquals("Matroska (MKV)", sections["Stream"]!!["Type"])
        assertEquals("12.3 s", sections["Stream"]!!["Buffered"])
        assertTrue(sections["Stream"]!!["Data received"]!!.startsWith("16.0 Mbps"))
        assertEquals("4K · H.265 (HEVC) · 5.1", StreamInfo.summary(s))
        // Nothing known yet: no empty sections.
        assertTrue(StreamInfo.sections(StreamStats()).isEmpty())
    }

    @Test
    fun containersAndDynamicRange() {
        assertEquals("HLS · MPEG-TS segments", StreamInfo.container("video/mp2t", "http://h/live/1.m3u8", true))
        assertEquals("HLS", StreamInfo.container(null, "http://h/live/1.m3u8", true))
        assertEquals("MPEG-TS", StreamInfo.container(null, "http://h/live/u/p/1.ts?token=x", false))
        assertEquals("MP4", StreamInfo.container(null, "http://h/movie/u/p/1.mp4", false))
        assertNull(StreamInfo.container(null, "http://h/stream", false))
        assertEquals("Dolby Vision", StreamInfo.dynamicRange("video/dolby-vision", null, -1))
        assertEquals("HLG", StreamInfo.dynamicRange("video/hevc", null, 7))
        assertEquals("SDR", StreamInfo.dynamicRange("video/avc", null, 3))
        assertNull(StreamInfo.dynamicRange("video/avc", null, -1))
    }
}
