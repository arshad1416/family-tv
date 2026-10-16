package app.familytv.ui

import app.familytv.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualSourceSelectionTest {
    @Test fun selectingIptvPreventsLateAlternateLaunchEvenIfProviderIgnoresCancellation() = runTest {
        val selection = ManualSourceSelection()
        val provider = CompletableDeferred<Unit>()
        var launches = 0
        val ticket = selection.begin()
        val job = launch {
            withContext(NonCancellable) {
                provider.await()
                try { selection.check(ticket); launches++ } catch (_: SourceUnavailable) {}
            }
        }
        runCurrent()
        selection.cancel() // synchronous IPTV/Close boundary, before composition disposal
        provider.complete(Unit)
        job.join()
        assertEquals(0, launches)
    }
    @Test fun actualResolverCannotLaunchWhenIptvIsChosenDuringPendingLinkResolution() = runTest {
        val selection = ManualSourceSelection()
        val pending = CompletableDeferred<Unit>()
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val target = DiscoveryTarget(DiscoveryTitle("movie", "42", "Open Film", 2024, imdb = "tt1234567"))
        val source = SourceCandidate(hash, 0, "Open Film 2024 1080p x264", true)
        var launches = 0
        val ticket = selection.begin()
        val resolver = RdSourceResolver({ path, _ ->
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC"}"""
                "/torrents/info/ABC" -> """{"hash":"$hash","filename":"Open Film 2024","status":"downloaded","files":[{"id":1,"path":"Open.Film.2024.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""
                "/unrestrict/link" -> { withContext(NonCancellable) { pending.await() }; """{"download":"https://cdn.example.test/video.mkv"}""" }
                else -> error("Unexpected path")
            }
        }, { selection.check(ticket) }, {})
        val job = launch {
            try {
                resolver.prepare(source, target) { selection.check(ticket) }
                selection.check(ticket)
                launches++
            } catch (_: CancellationException) {} catch (_: SourceUnavailable) {}
        }
        runCurrent()
        selection.cancel()
        job.cancel()
        pending.complete(Unit)
        job.join()
        assertEquals(0, launches)
    }

    @Test fun oldLookupCannotPublishAfterRetryOrProfileSwitch() {
        val selection = ManualSourceSelection()
        val old = selection.begin()
        val current = selection.begin()
        try { selection.check(old); fail("Stale request accepted") } catch (_: SourceUnavailable) {}
        selection.check(current)
        selection.cancel()
        try { selection.check(current); fail("Closed profile accepted") } catch (_: SourceUnavailable) {}
    }
}
