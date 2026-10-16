package app.familytv.discovery

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small, cancellable, bounded JSON requests. Errors intentionally contain no URL, body or token. */
class DiscoveryHttp(client: OkHttpClient) {
    private val client = client.newBuilder().callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    suspend fun request(request: Request, service: String): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SourceUnavailable("$service could not be reached. Try again later."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body
                        if (body != null && body.contentLength() > MAX_BYTES) throw SourceUnavailable("$service response is too large.")
                        val bytes = body?.source()?.let { source ->
                            source.request(MAX_BYTES + 1)
                            if (source.buffer.size > MAX_BYTES) throw SourceUnavailable("$service response is too large.")
                            source.readByteArray()
                        } ?: byteArrayOf()
                        if (continuation.isActive) continuation.resume(r.code to bytes.toString(Charsets.UTF_8))
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(SourceUnavailable("$service returned an unreadable or oversized response."))
                }
            }
        })
    }
    companion object { const val MAX_BYTES = 1_048_576L }
}
