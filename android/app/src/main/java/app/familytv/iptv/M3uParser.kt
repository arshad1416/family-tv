package app.familytv.iptv

import java.io.BufferedReader
import java.io.Reader

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val chno: Int?,
    val catchupDays: Int,
)

/**
 * Line-by-line M3U reader. It never holds the whole playlist in memory, which matters on
 * 1 GB Fire TV Sticks where provider playlists can be tens of megabytes.
 */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(reader: Reader, onEntry: (M3uEntry) -> Unit) {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        var info: String? = null
        var groupFromExtGrp: String? = null
        while (true) {
            val raw = br.readLine() ?: break
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; groupFromExtGrp = null }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> groupFromExtGrp = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                info != null -> {
                    onEntry(toEntry(info, groupFromExtGrp, line))
                    info = null
                }
            }
        }
    }

    private fun toEntry(info: String, extGrp: String?, url: String): M3uEntry {
        // The display name follows the first comma that is not inside a quoted attribute.
        val cut = nameComma(info)
        val header = if (cut >= 0) info.substring(0, cut) else info
        val attrs = attr.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val name = (if (cut >= 0) info.substring(cut + 1).trim() else "").ifBlank { attrs["tvg-name"] ?: url.substringAfterLast('/') }
        return M3uEntry(
            name = name,
            url = url,
            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGrp ?: "Uncategorised",
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"])?.toIntOrNull(),
            catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
        )
    }

    private fun nameComma(info: String): Int {
        var inQuote = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return -1
    }
}
