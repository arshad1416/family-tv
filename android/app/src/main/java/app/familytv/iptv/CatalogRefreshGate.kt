package app.familytv.iptv

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CatalogRefreshGate {
    private val lock = Mutex()
    suspend fun run(onlyIfStale: Boolean, stale: suspend () -> Boolean, refresh: suspend () -> Unit): Boolean = lock.withLock {
        if (onlyIfStale && !stale()) return@withLock false
        refresh()
        true
    }
}
