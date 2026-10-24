package app.familytv.ui

import android.content.Context
import app.rive.runtime.kotlin.core.Rive

/**
 * The Rive runtime behind the appearance switch and the startup splash, started on first use.
 * Where it can't start (no Rive library for this CPU, or the JVM tests) [ready] is false and the
 * drawn switch and the startup videos stand in.
 */
internal object RiveArt {
    private var ready: Boolean? = null

    fun ready(ctx: Context): Boolean = ready ?: runCatching { Rive.init(ctx.applicationContext) }.isSuccess.also { ready = it }
}
