package app.familytv.ui

import app.familytv.discovery.DiscoveryTitle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Used by ordinary Search after publishing its catalog results. No RD library/account lookup. */
internal suspend fun automaticMetadataSearch(
    query: String,
    search: suspend (String) -> List<DiscoveryTitle>,
    verify: suspend () -> Unit,
    filter: suspend (List<DiscoveryTitle>) -> List<DiscoveryTitle>,
): List<DiscoveryTitle> {
    if (query.trim().length < 2) return emptyList()
    delay(350)
    verify()
    val results = filter(search(query))
    verify()
    currentCoroutineContext().ensureActive()
    return results
}
