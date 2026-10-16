package app.familytv.player

import androidx.media3.common.MediaItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.familytv.subs.OpenSubtitles
import app.familytv.subs.SubtitleException
import app.familytv.subs.SubtitleHit
import app.familytv.subs.SubtitleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong
import java.io.File

/**
 * Search-then-pick flow for online subtitles, as Compose state for the Captions panel. Searches
 * run only when the viewer asks, so nothing is sent anywhere for titles nobody looks up.
 */
class OnlineSubtitles(
    private val client: OpenSubtitles,
    private val scope: CoroutineScope,
    private val dir: File,
    private val request: suspend () -> SubtitleRequest,
    private val onLoaded: (File, SubtitleHit) -> Unit,
    private val currentMedia: () -> MediaItem? = { null },
) {
    enum class Phase { Idle, Searching, Results, Empty, Loading, Error }

    var phase by mutableStateOf(Phase.Idle); private set
    var hits by mutableStateOf<List<SubtitleHit>>(emptyList()); private set
    var message by mutableStateOf<String?>(null); private set
    var loadedId by mutableStateOf<Long?>(null); private set
    private var job: Job? = null
    private val generation = AtomicLong()

    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        phase = if (hits.isEmpty()) Phase.Idle else Phase.Results
        message = null
    }

    private suspend fun ensureCurrent(ticket: Long, media: MediaItem?) {
        currentCoroutineContext().ensureActive()
        if (generation.get() != ticket || currentMedia() != media) throw CancellationException("Subtitle selection changed")
    }

    fun search() {
        job?.cancel()
        val ticket = generation.incrementAndGet()
        val media = currentMedia()
        job = scope.launch {
            phase = Phase.Searching
            message = null
            try {
                val found = client.search(request())
                ensureCurrent(ticket, media)
                hits = found
                phase = if (hits.isEmpty()) Phase.Empty else Phase.Results
            } catch (e: CancellationException) {
                if (generation.get() == ticket) phase = if (hits.isEmpty()) Phase.Idle else Phase.Results
                throw e
            } catch (e: Exception) {
                ensureCurrent(ticket, media)
                fail(e)
            }
        }
    }

    fun pick(hit: SubtitleHit) {
        job?.cancel()
        val ticket = generation.incrementAndGet()
        val media = currentMedia()
        job = scope.launch {
            phase = Phase.Loading
            message = null
            try {
                val file = client.download(hit, dir)
                ensureCurrent(ticket, media)
                loadedId = hit.fileId
                phase = Phase.Results
                onLoaded(file, hit)
            } catch (e: CancellationException) {
                if (generation.get() == ticket) phase = if (hits.isEmpty()) Phase.Idle else Phase.Results
                throw e
            } catch (e: Exception) {
                ensureCurrent(ticket, media)
                fail(e)
            }
        }
    }

    private fun fail(e: Exception) {
        message = (e as? SubtitleException)?.message ?: "Couldn't reach the subtitle service. Check the internet connection."
        phase = Phase.Error
    }
}
