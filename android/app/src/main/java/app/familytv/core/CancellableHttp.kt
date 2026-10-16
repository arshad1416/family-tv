package app.familytv.core

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** Cancellation closes the socket even while a blocking parser is consuming a slow response. */
internal suspend fun <T> Call.consumeCancellable(consume: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val owner = currentCoroutineContext()
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!owner.isActive) this@consumeCancellable.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                execute().use { consume(it) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                cancellation.cancel()
            }
        }
    }
