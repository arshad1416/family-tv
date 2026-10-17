package app.familytv.ui

/** The intended tune survives STOP; background retry jobs do not. */
internal class PlaybackResume {
    private var loaded = false
    private var pendingTune = false
    fun liveLoaded() { loaded = true }
    fun selected() { pendingTune = true }
    fun dispatched() { pendingTune = false }
    fun resume(live: Boolean, hasMedia: Boolean, load: () -> Unit, tune: () -> Unit, vod: () -> Unit, play: () -> Unit) {
        when {
            live && !loaded -> load()
            live && pendingTune -> tune()
            hasMedia -> play()
            live -> tune()
            else -> vod()
        }
    }
}
