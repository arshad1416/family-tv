package app.familytv.discovery

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Provider-reported progress; null means no trustworthy percentage is available. */
data class PreparationProgress(val stage: String, val percent: Double? = null)

/** Only called by explicit Play for one manually chosen source. Account checks stay local to that attempt. */
class RdSourceResolver(
    private val call: suspend (String, Map<String, String>?) -> String,
    private val verify: suspend () -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun prepare(source: SourceCandidate, target: DiscoveryTarget, status: (String) -> Unit): String =
        prepare(source, target, {}, status)

    suspend fun prepare(source: SourceCandidate, target: DiscoveryTarget, progress: (PreparationProgress) -> Unit, status: (String) -> Unit): String =
        prepare(source, target, progress, status, {})

    suspend fun prepare(source: SourceCandidate, target: DiscoveryTarget, progress: (PreparationProgress) -> Unit, status: (String) -> Unit, beforeReturn: suspend () -> Unit): String {
        val deadline = monotonicMs() + 8 * 60_000L
        fun pending(): Nothing = throw SourceUnavailable("This source is still preparing in your Real-Debrid account. Return later, choose another source, or play IPTV. Cancelling only stops local waiting; it does not delete a torrent already added to your account.")
        fun checkDeadline() { if (monotonicMs() >= deadline) pending() }
        fun report(stage: String, percent: Double? = null) {
            status(stage + (percent?.let { " ($it%)" } ?: ""))
            progress(PreparationProgress(stage, percent))
        }
        if (!Regex("[a-f0-9]{40}").matches(source.hash) || source.fileIdx?.let { it !in 0..9999 } == true) throw SourceUnavailable("Choose a source with a valid hash and file index.")
        target.streamId // validate identity before account mutation
        suspend fun request(path: String, form: Map<String, String>? = null): String {
            currentCoroutineContext().ensureActive()
            checkDeadline()
            verify()
            checkDeadline()
            return call(path, form).also {
                currentCoroutineContext().ensureActive()
                checkDeadline()
                verify()
                checkDeadline()
            }
        }
        suspend fun resolve(info: TorrentInfo, chosen: RdSourceFile): String {
            val selected = info.files.filter { it.selected }
            val position = selected.indexOfFirst { it.id == chosen.id }
            if (position < 0 || selected.size != info.links.size) throw SourceUnavailable("This torrent has an ambiguous file selection. Choose another source.")
            val link = info.links[position].takeIf(::safeHttps) ?: throw SourceUnavailable("The selected video has no unique playable link.")
            report("Resolving the selected video link…")
            val unrestrict = responseObject(request("/unrestrict/link", mapOf("link" to link)))
            if (selected.size > 1) {
                // Selected-file position is only a candidate mapping. Prove it identifies
                // the exact chosen file before exposing its address; never try a second link.
                if (unrestrict.string("filename") != sourceBasename(chosen.path) ||
                    unrestrict.integer("filesize")?.takeIf { it > 0 } != chosen.bytes) {
                    throw SourceUnavailable("Real-Debrid could not verify the selected video link. Choose another source.")
                }
            }
            val url = unrestrict.string("download")?.takeIf(::safeHttps) ?: throw SourceUnavailable("Real-Debrid gave no secure playable address.")
            checkDeadline()
            beforeReturn()
            currentCoroutineContext().ensureActive()
            verify()
            checkDeadline()
            return url
        }
        report("Checking existing account sources…")
        val recent = recentTorrents(request("/torrents?limit=20"))
        for (existing in recent.filter { it.hash.equals(source.hash, ignoreCase = true) && it.status == "downloaded" }) {
            val info = torrentInfo(request("/torrents/info/${existing.id}"), source.hash)
            if (info.status in setOf("error", "virus", "magnet_error", "dead")) throw SourceUnavailable("This source is unavailable on Real-Debrid. Choose another source.")
            if (info.status != "downloaded") continue
            val chosen = SourceRules.select(source, target, info.files, info.folder)
            if (!chosen.selected) continue
            report("Reusing the ready matching video…")
            return resolve(info, chosen)
        }
        report("Preparing a new selected source…")
        val added = responseObject(request("/torrents/addMagnet", mapOf("magnet" to "magnet:?xt=urn:btih:${source.hash}")))
        val id = added.string("id")?.takeIf { validId(it) } ?: throw SourceUnavailable("Real-Debrid did not identify the torrent.")
        if (recent.any { it.id == id }) throw SourceUnavailable("Real-Debrid returned an existing account source. Its file selection was left unchanged. Choose another source.")
        var selectedId: Int? = null
        repeat(120) { poll ->
            val info = torrentInfo(request("/torrents/info/$id"), source.hash)
            val state = info.status
            val files = info.files
            when (state) {
                "magnet_conversion" -> report("Checking torrent information…")
                "waiting_files_selection" -> {
                    val chosen = SourceRules.select(source, target, files, info.folder)
                    if (selectedId != null) throw SourceUnavailable("Real-Debrid did not retain the selected file. Try another source.")
                    report("Selecting the matching video…")
                    request("/torrents/selectFiles/$id", mapOf("files" to chosen.id.toString()))
                    selectedId = chosen.id
                }
                "queued", "downloading", "compressing", "uploading" -> {
                    val raw = info.raw.get("progress")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    val percent = raw?.takeIf { it.isNumber }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
                    report(when (state) {
                        "queued" -> "Queued on Real-Debrid…"
                        "downloading" -> "Downloading on Real-Debrid…"
                        else -> "Preparing the selected video…"
                    }, if (state == "downloading") percent else null)
                }
                "downloaded" -> {
                    val chosen = SourceRules.select(source, target, files, info.folder)
                    if (selectedId?.let { it != chosen.id } == true) throw SourceUnavailable("Real-Debrid did not retain the selected file. Try another source.")
                    return resolve(info, chosen)
                }
                "error", "virus", "magnet_error", "dead" -> throw SourceUnavailable("This source is unavailable on Real-Debrid. Choose another source.")
                else -> throw SourceUnavailable("Real-Debrid returned an unsupported preparation status.")
            }
            if (poll < 119) {
                currentCoroutineContext().ensureActive()
                checkDeadline()
                wait(minOf(if (poll < 3) 2000L else 5000L, (deadline - monotonicMs()).coerceAtLeast(0)))
            }
        }
        pending()
    }

    private data class AccountTorrent(val id: String, val hash: String, val status: String)
    private data class TorrentInfo(val raw: JsonObject, val status: String, val folder: String, val files: List<RdSourceFile>, val links: List<String>)

    private fun invalid(): Nothing = throw SourceUnavailable("Real-Debrid returned an invalid source response.")
    private fun validId(value: String) = Regex("[a-zA-Z0-9]{1,80}").matches(value)
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?.takeIf { it.isNotBlank() && it.length <= 8192 && it.none { c -> c.code < 32 || c.code == 127 } }
    private fun JsonObject.integer(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
        ?.takeIf { Regex("[0-9]+").matches(it) }?.toLongOrNull()
    private fun parse(json: String) = try {
        if (json.toByteArray(Charsets.UTF_8).size > DiscoveryHttp.MAX_BYTES) invalid()
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            JsonParser.parseReader(reader).also { if (reader.peek() != JsonToken.END_DOCUMENT) invalid() }
        }
    } catch (_: Exception) { invalid() }
    private fun responseObject(json: String): JsonObject = parse(json).takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
    private fun status(info: JsonObject): String = info.string("status")?.takeIf { it in setOf("magnet_conversion", "waiting_files_selection", "queued", "downloading", "compressing", "uploading", "downloaded", "error", "virus", "magnet_error", "dead") } ?: invalid()

    private fun recentTorrents(json: String): List<AccountTorrent> {
        val array = parse(json).takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
        if (array.size() > 20) invalid()
        // Validate the complete bounded response before touching any account candidate.
        return array.map { raw ->
            val item = raw.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
            AccountTorrent(item.string("id")?.takeIf(::validId) ?: invalid(),
                item.string("hash")?.takeIf { Regex("[a-fA-F0-9]{40}").matches(it) } ?: invalid(), status(item))
        }.also { if (it.map { row -> row.id }.distinct().size != it.size) invalid() }
    }

    private fun torrentInfo(json: String, expectedHash: String): TorrentInfo {
        val info = responseObject(json)
        val hash = info.string("hash") ?: invalid()
        if (!hash.equals(expectedHash, ignoreCase = true)) throw SourceUnavailable("Real-Debrid returned a different torrent.")
        val state = status(info)
        val required = state == "downloaded" || state == "waiting_files_selection"
        val folder = info.string("filename") ?: if (required || info.has("filename")) invalid() else ""
        val rawFiles = info.get("files")
        val files = if (rawFiles == null && !required) emptyList() else {
            val array = rawFiles?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
            if (array.size() > 10000) invalid()
            array.map { raw ->
                val file = raw.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
                val id = file.integer("id")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: invalid()
                val selected = file.integer("selected")?.takeIf { it in 0..1 } ?: invalid()
                RdSourceFile(id, file.string("path") ?: invalid(), file.integer("bytes") ?: invalid(), selected == 1L)
            }.also { if (it.map { f -> f.id }.distinct().size != it.size) invalid() }
        }
        val rawLinks = info.get("links")
        val links = if (rawLinks == null && state != "downloaded") emptyList() else {
            val array = rawLinks?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
            if (array.size() > 10000) invalid()
            array.map { raw -> raw.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(::safeHttps) ?: invalid() }
        }
        return TorrentInfo(info, state, folder, files, links)
    }

}

internal fun safeHttps(value: String): Boolean = try {
    val u = java.net.URI(value)
    value.length <= 8192 && value.none { it.code <= 32 || it.code == 127 } && u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null
} catch (_: Exception) { false }
