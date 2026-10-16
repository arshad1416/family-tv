package app.familytv.debrid

import app.familytv.iptv.QualityPref
import app.familytv.iptv.StreamQuality
import app.familytv.trakt.TitleMatch

/** One video file in the Real-Debrid account: its name, the torrent it came in, and its hoster link. */
data class RdFile(val name: String, val folder: String, val bytes: Long, val link: String)

/** A file matched to a catalog movie or episode, with what the Play button shows about it. */
data class RdCopy(val file: RdFile, val quality: Int?) {
    /** "1080p · 4.3 GB" */
    val label: String
        get() = listOfNotNull(quality?.let { if (it >= 2160) "4K" else "${it}p" }, size(file.bytes)).joinToString(" · ")

    companion object {
        fun size(bytes: Long): String? = when {
            bytes <= 0 -> null
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
            else -> "${bytes / 1_000_000} MB"
        }
    }
}

/** Release names ("Northern.Lights.S01E04.1080p.WEB-DL.mkv") as title, year, episode and quality. Pure, unit tested. */
object RdNames {
    data class Parsed(val title: String, val year: Int?, val season: Int?, val episode: Int?, val quality: Int?)

    private val VIDEO = setOf("mkv", "mp4", "avi", "m4v", "ts", "m2ts", "webm", "mov", "wmv", "mpg", "mpeg")
    private val EPISODE = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{2})\\b")
    private val SEASON_ONLY = Regex("(?i)\\b(?:S(\\d{1,2})|Season (\\d{1,2}))\\b")
    private val YEAR = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val QUALITY = Regex("(?i)\\b(2160|1080|720|576|480)[pi]\\b|\\b(4k|uhd)\\b")
    // Where the title ends when a name has no year or episode number.
    private val RELEASE_TAG = Regex(
        "(?i)\\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|blu-?ray|bdrip|brrip|web-?dl|webrip|web|hdtv|dvdrip|hdrip|remux|" +
            "x264|x265|h ?264|h ?265|hevc|avc|hdr|hdr10|dv|10bit|proper|repack|extended|unrated|imax|complete|multi)\\b",
    )
    private val LEADING_SITE = Regex("^\\s*(?:\\[[^]]*]|www \\S+ \\S+)\\s*-?\\s*")

    private val SURROUND = Regex("(?i)(?<![a-z])(atmos|truehd|dts|ddp|dd\\+|e-?ac-?3|ac-?3)(?![a-z])|(?<!\\d)[57][. ]1(?!\\d)")

    /** The name says surround sound: Atmos, TrueHD, DTS, DD+ / DDP / EAC3, AC3 / DD5.1, 5.1 or 7.1. */
    fun isSurround(name: String): Boolean = SURROUND.containsMatchIn(name)

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO

    /** Trailers and samples ride along in many torrents; they are never the film. */
    fun isExtra(name: String): Boolean = Regex("(?i)\\b(sample|trailer|featurette|extras?)\\b").containsMatchIn(name.replace('.', ' '))

    /** The file's own name first; the torrent's name gives the title when the file name has none. */
    fun parse(fileName: String, folder: String = ""): Parsed {
        val own = parseOne(fileName.substringAfterLast('/').let { if (isVideo(it)) it.substringBeforeLast('.') else it })
        if (folder.isBlank()) return own
        val outer = parseOne(folder)
        return own.copy(
            title = own.title.ifEmpty { outer.title },
            year = own.year ?: outer.year.takeIf { own.title.isEmpty() || TitleMatch.key(own.title) == TitleMatch.key(outer.title) },
            season = own.season ?: outer.season.takeIf { own.episode != null },
            quality = own.quality ?: outer.quality,
        )
    }

    private fun parseOne(raw: String): Parsed {
        val s = LEADING_SITE.replace(raw.replace('.', ' ').replace('_', ' '), "").replace(Regex("\\s+"), " ").trim()
        val ep = EPISODE.find(s)
        val season = ep?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toInt() }
            ?: SEASON_ONLY.find(s)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() }
        val episode = ep?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt() }
        // A year at the very start is the title ("2012", "1917 2019 1080p"), not its release year.
        val year = YEAR.findAll(s).firstOrNull { it.range.first > 0 && (ep == null || it.range.first < ep.range.first) }
        val cuts = listOfNotNull(ep?.range?.first, year?.range?.first, SEASON_ONLY.find(s)?.range?.first?.takeIf { it > 0 }, RELEASE_TAG.find(s)?.range?.first?.takeIf { it > 0 })
        val end = cuts.minOrNull() ?: s.length
        val title = s.substring(0, end).replace(Regex("[\\[(]\\s*$"), "").trim { it.isWhitespace() || it in "-([" }
        val q = QUALITY.find(s)?.let { m -> m.groupValues[1].toIntOrNull() ?: 2160 }
        return Parsed(title, year?.value?.toInt(), season, episode, q)
    }
}

/** A title in the account that Search found: a film (its best [copy]) or a show (its [episodes], best copy of each). */
data class RdTitle(val title: String, val year: Int?, val copy: RdCopy?, val episodes: List<RdEpisode>) {
    val isShow get() = copy == null
}

data class RdEpisode(val season: Int, val episode: Int, val copy: RdCopy) {
    /** "S01E04 · 1080p · 1.2 GB" */
    val label: String get() = listOf("S%02dE%02d".format(season, episode), copy.label).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * The account's files, indexed by title for the catalog to ask "is this movie or episode in Real-Debrid?".
 * Matching uses the same title rules as Trakt ([TitleMatch]): same title, year within one when both are known.
 * [bestFirst] (the "Play from Real-Debrid first" setting) picks the best copy: 4K, then surround, then size;
 * or, when Settings' Stream quality for films ([moviePref]) or shows ([seriesPref]) isn't Auto, the
 * copy nearest that quality, then surround, then size.
 */
class RdIndex(
    val files: List<RdFile>,
    private val bestFirst: Boolean = false,
    private val moviePref: QualityPref = QualityPref.AUTO,
    private val seriesPref: QualityPref = QualityPref.AUTO,
) {
    private data class Entry(val p: RdNames.Parsed, val file: RdFile) {
        val surround = RdNames.isSurround(file.name) || RdNames.isSurround(file.folder)
    }

    private val movies: Map<String, List<Entry>>
    private val episodes: Map<String, List<Entry>>
    val fileCount = files.size

    init {
        val all = files.filter { RdNames.isVideo(it.name) && !RdNames.isExtra(it.name) }.map { Entry(RdNames.parse(it.name, it.folder), it) }
            .filter { it.p.title.isNotEmpty() }
        movies = all.filter { it.p.episode == null }.groupBy { TitleMatch.key(it.p.title) }
        episodes = all.filter { it.p.episode != null }.groupBy { TitleMatch.key(it.p.title) }
    }

    /** The movie named [name] in the catalog ("EN - The Last Convoy (2023)"), when the account has it. */
    fun movie(name: String, year: Int?): RdCopy? {
        val c = TitleMatch.parse(name)
        val y = year ?: c.year
        return best(movies[TitleMatch.key(c.title)].orEmpty().filter { TitleMatch.same(c.title, y, it.p.title, it.p.year) }, moviePref)
    }

    /** Season [season] episode [episode] of the show named [seriesName], when the account has it. */
    fun episode(seriesName: String, year: Int?, season: Int, episode: Int): RdCopy? = best(
        showEntries(seriesName, year).filter { (it.p.season ?: 1) == season && it.p.episode == episode },
        seriesPref,
    )

    /** Whether any episode of the show is in the account. */
    fun hasShow(seriesName: String, year: Int?): Boolean = showEntries(seriesName, year).isNotEmpty()

    private fun showEntries(seriesName: String, year: Int?): List<Entry> {
        val c = TitleMatch.parse(seriesName)
        val y = year ?: c.year
        return episodes[TitleMatch.key(c.title)].orEmpty().filter { TitleMatch.same(c.title, y, it.p.title, it.p.year) }
    }

    /**
     * Titles in the account whose name holds [query], leaving out the ones in [skip] ([TitleMatch.key]s
     * of what the catalog already found). Films are one per title and year; shows list their episodes.
     */
    fun search(query: String, skip: Set<String>, limit: Int = 20): List<RdTitle> {
        val q = TitleMatch.key(query)
        if (q.isEmpty()) return emptyList()
        fun hit(key: String) = key !in skip && key.contains(q)
        val films = movies.filterKeys(::hit).values.flatMap { e -> e.groupBy { it.p.year }.values }
            .mapNotNull { e -> best(e, moviePref)?.let { RdTitle(e.first().p.title, e.first().p.year, it, emptyList()) } }
        val shows = episodes.filterKeys(::hit).values.map { e ->
            val eps = e.groupBy { (it.p.season ?: 1) to it.p.episode!! }
                .mapNotNull { (se, copies) -> best(copies, seriesPref)?.let { RdEpisode(se.first, se.second, it) } }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            RdTitle(e.first().p.title, e.firstNotNullOfOrNull { it.p.year }, null, eps)
        }
        return (films + shows).sortedWith(compareBy({ !TitleMatch.key(it.title).startsWith(q) }, { it.title.lowercase() })).take(limit)
    }

    /**
     * Normally 1080p and 720p first (what every TV plays smoothly), then 4K, then the rest; the biggest
     * file of those. With [bestFirst]: 4K, 1080p, 720p, the rest (or nearest [pref] when it isn't Auto);
     * then surround sound; then the biggest.
     */
    private fun best(entries: List<Entry>, pref: QualityPref): RdCopy? = entries
        .maxWithOrNull(
            when {
                !bestFirst -> compareBy<Entry>({ rank(it.p.quality) }, { it.file.bytes })
                pref == QualityPref.AUTO -> compareBy<Entry>({ bestRank(it.p.quality) }, { it.surround }, { it.file.bytes })
                else -> compareBy<Entry>({ -StreamQuality.score(StreamQuality.ofHeight(it.p.quality ?: 0), pref) }, { it.surround }, { it.file.bytes })
            },
        )
        ?.let { RdCopy(it.file, it.p.quality) }

    private fun rank(q: Int?): Int = when {
        q == null -> 1
        q in 720..1080 -> 3 + q / 100
        q >= 2160 -> 2
        else -> 1
    }

    private fun bestRank(q: Int?): Int = when {
        q == null -> 0
        q >= 2160 -> 3
        q >= 1080 -> 2
        q >= 720 -> 1
        else -> 0
    }

    companion object {
        val EMPTY = RdIndex(emptyList())
    }
}
