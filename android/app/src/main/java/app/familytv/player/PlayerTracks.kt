package app.familytv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.familytv.iptv.QualityPref
import app.familytv.iptv.StreamQuality
import java.util.Locale

/** One selectable track in a picker. */
class TrackChoice(
    val type: Int,
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val selected: Boolean,
    val language: String?,
    val formatId: String?,
    val height: Int = -1,
    val bitrate: Int = -1,
)

/** Label suffix of side-loaded online subtitles, so the picker can find the track after re-preparing. */
const val ONLINE_SUFFIX = "(online)"

/** Captions on/off and the preferred caption language, kept across sessions and channels. */
class CaptionPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    var on: Boolean
        get() = prefs.getBoolean("captionsOn", false)
        set(v) = prefs.edit().putBoolean("captionsOn", v).apply()
    var language: String?
        get() = prefs.getString("captionLanguage", null)
        set(v) = prefs.edit().putString("captionLanguage", v).apply()
}

/**
 * The audio, video and text tracks of what is playing, as Compose state for the pickers, and the
 * TrackSelectionParameters changes behind each choice. Captions follow the saved preference on
 * every new item: off disables the text renderer; on prefers the saved language, accepts
 * untagged broadcast captions (CEA-608), and otherwise falls back to the first text track.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerTracks(private val player: Player, private val prefs: CaptionPrefs) {
    var text by mutableStateOf<List<TrackChoice>>(emptyList()); private set
    var audio by mutableStateOf<List<TrackChoice>>(emptyList()); private set
    var video by mutableStateOf<List<TrackChoice>>(emptyList()); private set
    var videoAuto by mutableStateOf(true); private set
    var captionsOn by mutableStateOf(prefs.on); private set
    /** True once any caption text has been shown for the current item. */
    var cuesSeen by mutableStateOf(false)

    private var autoPicked = false
    private var pendingTextId: String? = null
    /** Settings' Stream quality for what is playing; not Auto starts on the nearest video rendition once per item. */
    var preferredVideo = QualityPref.AUTO
    private var videoPicked = false

    /** Audio or video has something to choose between. */
    val hasChoices get() = audio.size > 1 || video.size > 1

    fun onNewItem() {
        autoPicked = false
        videoPicked = false
        cuesSeen = false
        videoAuto = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .applyCaptions(captionsOn)
            .build()
    }

    private fun androidx.media3.common.TrackSelectionParameters.Builder.applyCaptions(on: Boolean) = apply {
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
        val langs = listOfNotNull(prefs.language, Locale.getDefault().language.takeIf { it.isNotEmpty() }).distinct()
        setPreferredTextLanguages(*langs.toTypedArray())
        setSelectUndeterminedTextLanguage(true)
    }

    fun update(tracks: Tracks) {
        val t = ArrayList<TrackChoice>()
        val a = ArrayList<TrackChoice>()
        val v = ArrayList<TrackChoice>()
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val sel = g.isTrackSelected(i)
                when (g.type) {
                    C.TRACK_TYPE_TEXT -> t += TrackChoice(g.type, g, i, TrackLabels.text(f.language, f.label, f.sampleMimeType, f.codecs, f.accessibilityChannel, t.size), sel, f.language, f.id)
                    C.TRACK_TYPE_AUDIO -> a += TrackChoice(g.type, g, i, TrackLabels.audio(f.language, f.label, f.sampleMimeType, f.codecs, f.channelCount, a.size), sel, f.language, f.id)
                    C.TRACK_TYPE_VIDEO -> v += TrackChoice(
                        g.type, g, i, TrackLabels.video(f.width, f.height, f.bitrate, f.frameRate, f.sampleMimeType, f.codecs, v.size), sel, null, f.id, f.height, f.bitrate,
                    )
                }
            }
        }
        text = t
        audio = a
        video = v.sortedWith(compareByDescending<TrackChoice> { it.height }.thenByDescending { it.bitrate }).distinctBy { it.label }
        videoAuto = player.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }
        if (!videoPicked && preferredVideo != QualityPref.AUTO && video.size > 1) {
            videoPicked = true
            // Picture & sound can still change it for this video.
            selectVideo(video[StreamQuality.pick(video.map { StreamQuality.ofHeight(it.height) }, preferredVideo)])
        }

        pendingTextId?.let { id ->
            t.firstOrNull { it.formatId == id || it.formatId?.endsWith(":$id") == true || it.label.contains(ONLINE_SUFFIX) }?.let { pendingTextId = null; selectText(it); return }
        }
        // Captions are on but the preferred language isn't offered: show the first track instead.
        if (captionsOn && !autoPicked && t.isNotEmpty() && t.none { it.selected }) {
            autoPicked = true
            override(t.first())
        }
    }

    fun selectedText(): TrackChoice? = text.firstOrNull { it.selected }

    fun captionsOff() {
        captionsOn = false
        prefs.on = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    /** CC key and quick toggles: on uses the saved language, or the first track. */
    fun toggleCaptions() {
        if (captionsOn) return captionsOff()
        captionsOn = true
        prefs.on = true
        autoPicked = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).applyCaptions(true).build()
    }

    fun selectText(c: TrackChoice) {
        captionsOn = true
        prefs.on = true
        c.language?.takeIf { it.isNotBlank() && it != "und" }?.let { prefs.language = it }
        override(c, enableText = true)
    }

    fun selectAudio(c: TrackChoice) = override(c)

    /** null = Auto (adaptive). */
    fun selectVideo(c: TrackChoice?) {
        if (c == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            videoAuto = true
        } else {
            override(c)
            videoAuto = false
        }
    }

    /** Select the side-loaded text track with this id once it shows up in the tracks. */
    fun expectText(id: String) {
        pendingTextId = id
        update(player.currentTracks)
    }

    private fun override(c: TrackChoice, enableText: Boolean = false) {
        val b = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
        if (enableText) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = b.build()
    }

    /** "Off", "On · English", "On (none in this stream)". */
    fun captionSummary(): String = when {
        !captionsOn -> "Off"
        text.isEmpty() -> "On · none in this stream"
        else -> "On · " + (selectedText()?.label ?: "waiting for captions")
    }
}
