package app.familytv.discovery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Provider-reported progress; null means no trustworthy percentage is available. */
data class PreparationProgress(val stage: String, val percent: Double? = null)

/** Only called by explicit Play for one manually chosen source. No speculative cache checks/adds. */
class RdSourceResolver(
    private val call: suspend (String, Map<String, String>?) -> String,
    private val verify: suspend () -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun prepare(source: SourceCandidate, target: DiscoveryTarget, status: (String) -> Unit): String =
        prepare(source, target, {}, status)

    suspend fun prepare(source: SourceCandidate, target: DiscoveryTarget, progress: (PreparationProgress) -> Unit, status: (String) -> Unit): String {
        fun report(stage: String, percent: Double? = null) {
            status(stage + (percent?.let { " ($it%)" } ?: ""))
            progress(PreparationProgress(stage, percent))
        }
        if (!Regex("[a-f0-9]{40}").matches(source.hash) || source.fileIdx?.let { it !in 0..9999 } == true) throw SourceUnavailable("Choose a source with a valid hash and file index.")
        target.streamId // validate identity before account mutation
        suspend fun request(path: String, form: Map<String, String>? = null): String {
            currentCoroutineContext().ensureActive()
            verify()
            return call(path, form).also { currentCoroutineContext().ensureActive(); verify() }
        }
        report("Checking the selected source…")
        val added = root(request("/torrents/addMagnet", mapOf("magnet" to "magnet:?xt=urn:btih:${source.hash}")))
        val id = added.text("id")?.takeIf { Regex("[a-zA-Z0-9]{1,80}").matches(it) } ?: throw SourceUnavailable("Real-Debrid did not identify the torrent.")
        var selectedId: Int? = null
        repeat(12) { poll ->
            val info = root(request("/torrents/info/$id"))
            if (!info.text("hash").equals(source.hash, ignoreCase = true)) throw SourceUnavailable("Real-Debrid returned a different torrent.")
            val state = info.text("status") ?: throw SourceUnavailable("Real-Debrid returned no torrent status.")
            val files = info.array("files").take(10001).mapNotNull { e ->
                val f = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                RdSourceFile(f.text("id")?.toIntOrNull() ?: return@mapNotNull null, f.text("path") ?: "", f.text("bytes")?.toLongOrNull() ?: 0, f.text("selected") == "1")
            }
            when (state) {
                "magnet_conversion" -> report("Checking torrent information…")
                "waiting_files_selection" -> {
                    val chosen = SourceRules.select(source, target, files, info.text("filename").orEmpty())
                    if (selectedId != null) throw SourceUnavailable("Real-Debrid did not retain the selected file. Try another source.")
                    report("Selecting the matching video…")
                    request("/torrents/selectFiles/$id", mapOf("files" to chosen.id.toString()))
                    selectedId = chosen.id
                }
                "queued", "downloading", "compressing", "uploading" -> {
                    val raw = info.get("progress")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    val percent = raw?.takeIf { it.isNumber }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
                    report(when (state) {
                        "queued" -> "Queued on Real-Debrid…"
                        "downloading" -> "Downloading on Real-Debrid…"
                        else -> "Preparing the selected video…"
                    }, if (state == "downloading") percent else null)
                }
                "downloaded" -> {
                    val chosen = SourceRules.select(source, target, files, info.text("filename").orEmpty())
                    val selected = files.filter { it.selected }
                    // Select one verified file; never guess archive or multi-link ordering.
                    if (!chosen.selected || selected.size != 1 || selected.single().id != chosen.id || selectedId?.let { it != chosen.id } == true) throw SourceUnavailable("This torrent has an ambiguous file selection. Choose another source.")
                    val links = info.array("links").mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
                    val link = links.singleOrNull()?.takeIf { safeHttps(it) } ?: throw SourceUnavailable("The selected video has no unique playable link.")
                    report("Resolving the selected video link…")
                    val unrestrict = root(request("/unrestrict/link", mapOf("link" to link)))
                    return unrestrict.text("download")?.takeIf { safeHttps(it) } ?: throw SourceUnavailable("Real-Debrid gave no secure playable address.")
                }
                "error", "virus", "magnet_error", "dead" -> throw SourceUnavailable("This source is unavailable on Real-Debrid. Choose another source.")
                else -> throw SourceUnavailable("Real-Debrid returned an unsupported preparation status.")
            }
            if (poll < 11) wait(if (poll < 3) 2000L else 5000L)
        }
        throw SourceUnavailable("This source is still preparing in your Real-Debrid account. Return later, choose another source, or play IPTV. Cancelling only stops local waiting; it does not delete a torrent already added to your account.")
    }
}

internal fun safeHttps(value: String): Boolean = try {
    val u = java.net.URI(value)
    value.length <= 8192 && value.none { it.code <= 32 || it.code == 127 } && u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null
} catch (_: Exception) { false }
