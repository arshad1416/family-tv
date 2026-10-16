package app.familytv.ui

import androidx.room.withTransaction
import app.familytv.data.AppDb
import app.familytv.data.Kind
import app.familytv.discovery.DiscoveryPolicy
import app.familytv.discovery.DiscoveryTitle
import app.familytv.discovery.SourceUnavailable
import app.familytv.portal.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Matches only finite current candidates, scanning complete catalog snapshots in small pages. */
internal class CatalogDiscoveryGuard(private val db: AppDb, private val profile: Profile) {
    private data class Candidate(val name: String, val kind: String? = null, val tmdb: String? = null)

    suspend fun filterNames(names: List<String>): Set<String> {
        val allowed = allowedIndices(names.map { Candidate(it) })
        return names.filterIndexed { i, _ -> i in allowed }.toSet()
    }

    suspend fun allowedName(name: String) = name in filterNames(listOf(name))

    suspend fun filterTitles(titles: List<DiscoveryTitle>): List<DiscoveryTitle> {
        val allowed = allowedIndices(titles.map { Candidate(it.title, if (it.kind == "movie") Kind.MOVIE else Kind.SERIES, it.tmdb) })
        return titles.filterIndexed { i, _ -> i in allowed }
    }

    suspend fun allowed(title: DiscoveryTitle) = filterTitles(listOf(title)).isNotEmpty()

    private suspend fun allowedIndices(candidates: List<Candidate>): Set<Int> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptySet()
        val names = HashMap<String, MutableSet<Int>>()
        val ids = HashMap<Pair<String, String>, MutableSet<Int>>()
        candidates.forEachIndexed { i, c ->
            DiscoveryPolicy.keys(c.name).forEach { names.getOrPut(it) { HashSet() } += i }
            if (c.kind != null && c.tmdb != null) ids.getOrPut(c.kind to c.tmdb) { HashSet() } += i
        }
        try {
            // A read transaction prevents a concurrent refresh moving rows behind the cursor.
            // Only one page plus the finite candidate/category sets stays in memory.
            db.withTransaction {
                val denied = HashSet<Int>()
                val dao = db.catalog()
                for (kind in listOf(Kind.MOVIE, Kind.SERIES)) {
                    val visible = categoryKeys(visibleCategories(dao.policyCategories(kind), profile))
                    var from = Long.MIN_VALUE
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val page = if (kind == Kind.MOVIE) dao.moviePolicyPage(from, PAGE) else dao.showPolicyPage(from, PAGE)
                        for (row in page) {
                            currentCoroutineContext().ensureActive()
                            if ((row.sourceId to row.categoryId) in visible) continue
                            DiscoveryPolicy.keys(row.name).forEach { key -> names[key]?.let(denied::addAll) }
                            row.tmdb?.let { id -> ids[kind to id]?.let(denied::addAll) }
                            if (denied.size == candidates.size) return@withTransaction emptySet<Int>()
                        }
                        if (page.size < PAGE || page.last().rowId == Long.MAX_VALUE) break
                        from = page.last().rowId + 1
                    }
                }
                candidates.indices.filterTo(HashSet()) { it !in denied }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw SourceUnavailable("Cannot verify the catalog policy. Try again later.") }
    }

    companion object { private const val PAGE = 1000 }
}
