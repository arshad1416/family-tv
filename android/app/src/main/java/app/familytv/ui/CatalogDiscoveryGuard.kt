package app.familytv.ui

import app.familytv.data.CatalogDao
import app.familytv.data.Kind
import app.familytv.discovery.DiscoveryPolicy
import app.familytv.discovery.DiscoveryTitle
import app.familytv.trakt.TitleMatch
import app.familytv.portal.Profile
import kotlinx.coroutines.flow.first

/** A bounded local-policy check shared by RD-only results and external metadata discovery. */
internal class CatalogDiscoveryGuard(private val dao: CatalogDao, private val profile: Profile) {

    suspend fun blocked(query: String = ""): Set<String>? {
      return try {
        val movieKeys = visibleKeys(Kind.MOVIE)
        val showKeys = visibleKeys(Kind.SERIES)
        if (movieKeys.size > 900 || showKeys.size > 900) return null
        val movies = dao.restrictedMovieTitles(movieKeys, LIMIT + 1)
        val shows = dao.restrictedShowTitles(showKeys, LIMIT + 1)
        // Do not expose an unchecked title if a broad query hit the policy-check bound.
        if (movies.size > LIMIT || shows.size > LIMIT) return null
        return (movies.map { it.name } + shows.map { it.name })
            .flatMapTo(HashSet()) { DiscoveryPolicy.keys(it) }
      } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun allowed(title: DiscoveryTitle): Boolean {
        val blocked = blocked(title.title) ?: return false
        if (!DiscoveryPolicy.allowed(title.title, blocked)) return false
        // TMDB identity catches renamed/translated provider copies as well.
        val kind = if (title.kind == "movie") Kind.MOVIE else Kind.SERIES
        val visible = categoryKeys(visibleCategories(dao.categories(kind).first(), profile))
        return if (kind == Kind.MOVIE) dao.moviesByTmdb(listOf(title.tmdb)).none { (it.sourceId to it.categoryId) !in visible }
        else dao.seriesByTmdb(listOf(title.tmdb)).none { (it.sourceId to it.categoryId) !in visible }
    }

    private suspend fun visibleKeys(kind: String) = visibleCategories(dao.categories(kind).first(), profile)
        .map { "${it.sourceId.toByteArray(Charsets.UTF_8).size}:${it.sourceId}${it.categoryId}" }

    companion object { private const val LIMIT = 20000 }
}
