package app.familytv.core

import android.content.Context
import app.familytv.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash's stack trace so the next heartbeat can send it to the portal: the TVs
 * have no other way to tell anyone why the app closed.
 */
object CrashLog {
    private const val PREFS = "crash"
    private const val KEEP_MS = 3 * 24 * 3_600_000L

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("trace", "${BuildConfig.VERSION_NAME} on ${thread.name}: " + trace.take(6000))
                    .putLong("at", System.currentTimeMillis())
                    .commit() // the process dies right after; apply() might not get written
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The last crash, if it was in the last few days: "version on thread: trace" and when. */
    fun last(ctx: Context): Pair<String, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0)
        if (System.currentTimeMillis() - at > KEEP_MS) return null
        return p.getString("trace", null)?.let { it to at }
    }
}
