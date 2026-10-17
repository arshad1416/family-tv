package app.familytv.ui

import androidx.media3.common.Player

internal fun shouldKeepPlayerScreenOn(started: Boolean, playWhenReady: Boolean, state: Int, failed: Boolean): Boolean =
    started && playWhenReady && !failed && (state == Player.STATE_BUFFERING || state == Player.STATE_READY)
