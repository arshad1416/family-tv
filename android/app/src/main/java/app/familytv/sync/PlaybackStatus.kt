package app.familytv.sync

/** What this TV is doing right now; sent to the portal with every heartbeat. */
object PlaybackStatus {
    @Volatile var title: String? = null
    @Volatile var startedAt: Long = 0
    @Volatile var bufferingEvents: Int = 0
    @Volatile var bitrateKbps: Int? = null
    @Volatile var droppedFrames: Int = 0
    @Volatile var lastError: String? = null
    @Volatile var lastErrorAt: Long = 0
    @Volatile var lastRefresh: Long = 0
    @Volatile var lastRefreshError: String? = null
    @Volatile var update: String? = null
    /** The last crash's stack trace (see [app.familytv.core.CrashLog]), so the admin can see why the app closed. */
    @Volatile var lastCrash: String? = null
    @Volatile var lastCrashAt: Long = 0
    /** How the last refresh used the extra guides (see [app.familytv.iptv.Catalog]); null when there are none. */
    @Volatile var guide: Map<String, Any?>? = null
    /** The last new-episodes check: per favourite show, newest episode, furthest watched and the count (see [NewEpisodes]). */
    @Volatile var followed: Map<String, Any?>? = null
    /** "ready" or "needs-permission" when the profile has Open when the TV turns on; null when it's off. */
    @Volatile var openOnStart: String? = null
    /** The last automatic server switch, host names only (see [app.familytv.iptv.ServerSwitch]). */
    @Volatile var serverSwitch: Map<String, Any?>? = null

    fun started(t: String) {
        title = t; startedAt = System.currentTimeMillis(); bufferingEvents = 0; bitrateKbps = null; droppedFrames = 0
    }

    fun stopped() { title = null }

    fun error(e: String) { lastError = e; lastErrorAt = System.currentTimeMillis() }

    fun snapshot(): Map<String, Any?> = mapOf(
        "playing" to title?.let { mapOf("title" to it, "since" to startedAt, "buffering" to bufferingEvents, "kbps" to bitrateKbps, "dropped" to droppedFrames) },
        "lastError" to lastError?.takeIf { System.currentTimeMillis() - lastErrorAt < 6 * 3600_000 },
        "lastRefresh" to lastRefresh,
        "lastRefreshError" to lastRefreshError,
        "update" to update,
        // The portal keeps 8,000 characters of this, so the trace leaves room for the rest.
        "lastCrash" to lastCrash?.let { mapOf("at" to lastCrashAt, "trace" to it.take(4000)) },
        "guide" to guide,
        "followed" to followed,
        "openOnStart" to openOnStart,
        "serverSwitch" to serverSwitch,
    )
}
