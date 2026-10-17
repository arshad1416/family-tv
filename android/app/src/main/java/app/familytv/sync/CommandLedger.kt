package app.familytv.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An interrupted acknowledgement must not replay a completed command. Memory remains bounded. */
internal class CommandLedger(private val capacity: Int = 100) {
    private val lock = Mutex()
    private val seen = LinkedHashSet<String>()
    private val pending = LinkedHashMap<String, Any?>()
    suspend fun run(id: String, action: suspend () -> Any?, acknowledge: suspend (Any?) -> Unit) = lock.withLock {
        currentCoroutineContext().ensureActive()
        if (id !in seen) {
            val result = action() // An incomplete operation is deliberately not recorded.
            seen.add(id)
            pending[id] = result
            while (seen.size > capacity) {
                val oldest = seen.firstOrNull { it !in pending } ?: seen.first()
                seen.remove(oldest)
                pending.remove(oldest)
            }
        }
        if (pending.containsKey(id)) {
            currentCoroutineContext().ensureActive()
            try { acknowledge(pending[id]); pending.remove(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the completed result for the next acknowledgement attempt. */ }
        }
    }
}
