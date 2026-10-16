package app.familytv.ui

import app.familytv.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Catalog orchestration: no source request can use authorization captured before metadata awaited. */
internal suspend fun catalogReleaseLookup(
    metadata: suspend () -> DiscoveryTitle?,
    allowed: suspend (DiscoveryTitle) -> Boolean,
    target: (DiscoveryTitle) -> DiscoveryTarget,
    verify: suspend () -> Unit,
    sources: suspend (DiscoveryTarget) -> List<SourceCandidate>,
): Pair<DiscoveryTarget, List<SourceCandidate>>? {
    val title = metadata() ?: return null
    if (!allowed(title)) throw SourceUnavailable("This title is hidden by the current catalog policy.")
    val selected = target(title)
    verify()
    currentCoroutineContext().ensureActive()
    val found = sources(selected)
    verify()
    currentCoroutineContext().ensureActive()
    return selected to found
}

/** Keep remote episode rows private until the current session, policy and selection authorize them. */
internal suspend fun loadVerifiedEpisodes(
    title: DiscoveryTitle,
    season: Int,
    fetch: suspend (DiscoveryTitle, Int) -> List<DiscoveryEpisode>,
    verify: suspend () -> Unit,
    allowed: suspend (DiscoveryTitle) -> Boolean,
    current: () -> Boolean,
    publish: (List<DiscoveryEpisode>) -> Unit,
) {
    val loaded = fetch(title, season)
    verify()
    if (!allowed(title)) throw SourceUnavailable("This title is hidden by the current catalog policy.")
    currentCoroutineContext().ensureActive()
    if (!current()) throw SourceUnavailable("The profile or selection changed. Choose the season again.")
    publish(loaded)
}
