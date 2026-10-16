package app.familytv.ui

import app.familytv.discovery.SourceUnavailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolve may suspend; the selection is checked immediately before the non-suspending player launch. */
internal class IptvSourcePlayback(
    private val resolve: suspend () -> String?,
    private val open: (String) -> Unit,
) {
    suspend operator fun invoke(beforeOpen: () -> Unit = {}) {
        val url = resolve() ?: throw SourceUnavailable("This IPTV link is unavailable. Choose another source.")
        currentCoroutineContext().ensureActive()
        beforeOpen()
        open(url)
    }
}
