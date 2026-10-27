package app.familytv.ui

import android.content.Context
import app.rive.runtime.kotlin.core.Rive

/**
 * The Rive runtime behind the tab icons, page wipe, appearance switch, player loader and
 * channel flash, started on first use. Where it can't start (no Rive library for this
 * CPU, or the JVM tests) [ready] is false and the drawn and static stand-ins show. Never on a TV:
 * the Kitchen's Fire Stick 4K crashed in Amazon's surface hooks as Rive released a view's surface
 * (its render thread, SharedSurface.release; Oct 27), and the onn's tab icon surfaces came back
 * blank or ghosted on return from Settings (rounds 15 and 16). Phones and tablets keep it.
 */
internal object RiveArt {
    private var ready: Boolean? = null

    fun ready(ctx: Context): Boolean = ready
        ?: (allowed(ctx) && runCatching { Rive.init(ctx.applicationContext) }.isSuccess).also { ready = it }

    /** Rive may run here at all: not on a TV. */
    fun allowed(ctx: Context): Boolean = !ctx.isTelevision()
}
