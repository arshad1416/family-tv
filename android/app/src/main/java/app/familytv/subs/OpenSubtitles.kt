package app.familytv.subs

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** A failure with a message fit to show on the TV. */
class SubtitleException(message: String) : IOException(message)

/**
 * OpenSubtitles.com REST API v1 client: search, then ask for a download link and save the .srt.
 * The API key comes from the portal (a Worker secret), never from the app build.
 */
class OpenSubtitles(
    private val apiKey: String,
    private val userAgent: String,
    private val client: OkHttpClient,
    private val baseUrl: String = BASE_URL,
) {
    /** Tries the id searches first and falls back to the title; returns ranked results. */
    suspend fun search(r: SubtitleRequest): List<SubtitleHit> = withContext(Dispatchers.IO) {
        for (params in SubtitleSearch.queries(r)) {
            val url = "${baseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val hits = SubtitleSearch.rank(SubtitleSearch.parse(call(Request.Builder().url(url).get())), r)
            if (hits.isNotEmpty()) return@withContext hits.take(MAX_RESULTS)
        }
        emptyList()
    }

    /** Fetches [hit] as SRT into [dir] and returns the file. */
    suspend fun download(hit: SubtitleHit, dir: File): File = withContext(Dispatchers.IO) {
        val body = """{"file_id":${hit.fileId},"sub_format":"srt"}""".toRequestBody(JSON)
        val res = JsonParser.parseString(call(Request.Builder().url("${baseUrl.trimEnd('/')}/download").post(body))).asJsonObject
        val link = res.get("link")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw SubtitleException(res.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "No download link was returned.")
        dir.mkdirs()
        // Keep only the latest few downloads.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val out = File(dir, "os-${hit.fileId}.srt")
        client.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute().use { r ->
            if (!r.isSuccessful) throw SubtitleException("The subtitle file couldn't be downloaded (HTTP ${r.code}).")
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw SubtitleException("The subtitle file was empty.")
            out.writeBytes(bytes)
        }
        out
    }

    private fun call(b: Request.Builder): String {
        val req = b.header("Api-Key", apiKey).header("User-Agent", userAgent).header("Accept", "application/json").build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.isSuccessful) return text
            val msg = runCatching { JsonParser.parseString(text).asJsonObject.get("message")?.asString }.getOrNull()
            throw SubtitleException(
                when (res.code) {
                    401, 403 -> "The subtitle service rejected this TV's key. Check OPENSUBTITLES_API_KEY in the portal."
                    406, 429 -> msg ?: "Today's subtitle download limit is used up. Try again tomorrow."
                    else -> msg ?: "The subtitle service answered HTTP ${res.code}."
                },
            )
        }
    }

    companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val MAX_RESULTS = 20
        private val JSON = "application/json".toMediaType()
    }
}
