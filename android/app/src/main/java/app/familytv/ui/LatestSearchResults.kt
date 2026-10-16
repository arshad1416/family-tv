package app.familytv.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/** Owns one Search screen's query generations, including responses that arrive after cancellation. */
internal class LatestSearchResults<T>(private val empty: T) {
    private val generation = AtomicLong()

    suspend fun load(local: suspend () -> T, enrich: suspend (T) -> T, publish: (T) -> Unit) {
        currentCoroutineContext().ensureActive()
        val own = generation.incrementAndGet()
        publish(empty)
        val rows = local()
        currentCoroutineContext().ensureActive()
        if (own != generation.get()) return
        publish(rows)
        val enriched = try { enrich(rows) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return }
        currentCoroutineContext().ensureActive()
        if (own == generation.get()) publish(enriched)
    }
}
