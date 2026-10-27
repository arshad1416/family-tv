package app.familytv.ui

import android.content.Context
import app.rive.runtime.kotlin.core.Rive

/**
 * The Rive runtime behind the tab icons, page wipe, appearance switch, player loader and
 * channel flash, started on first use. Where it can't start (no Rive library for this
 * CPU, or the JVM tests) [ready] is false and the drawn and static stand-ins show. Never on Fire OS:
 * the Kitchen's Fire Stick 4K crashed in Amazon's surface hooks as Rive released a view's surface
 * (its render thread, SharedSurface.release; Oct 27).
 */
internal object RiveArt {
    private var ready: Boolean? = null

    fun ready(ctx: Context): Boolean = ready
        ?: (!isFireTv(ctx) && runCatching { Rive.init(ctx.applicationContext) }.isSuccess).also { ready = it }
}
