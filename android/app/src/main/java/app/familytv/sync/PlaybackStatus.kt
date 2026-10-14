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
    )
}
