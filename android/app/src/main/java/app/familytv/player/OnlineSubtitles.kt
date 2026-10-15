package app.familytv.player

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
) {
    enum class Phase { Idle, Searching, Results, Empty, Loading, Error }

    var phase by mutableStateOf(Phase.Idle); private set
    var hits by mutableStateOf<List<SubtitleHit>>(emptyList()); private set
    var message by mutableStateOf<String?>(null); private set
    var loadedId by mutableStateOf<Long?>(null); private set
    private var job: Job? = null

    fun search() {
        job?.cancel()
        job = scope.launch {
            phase = Phase.Searching
            message = null
            try {
                hits = client.search(request())
                phase = if (hits.isEmpty()) Phase.Empty else Phase.Results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun pick(hit: SubtitleHit) {
        job?.cancel()
        job = scope.launch {
            phase = Phase.Loading
            message = null
            try {
                val file = client.download(hit, dir)
                loadedId = hit.fileId
                phase = Phase.Results
                onLoaded(file, hit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fail(e: Exception) {
        message = (e as? SubtitleException)?.message ?: "Couldn't reach the subtitle service. Check the internet connection."
        phase = Phase.Error
    }
}
