package app.familytv.ui

import app.familytv.discovery.SourceUnavailable
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IptvSourcePlaybackTest {
    @Test fun alternateChoiceRejectsLateNoncooperativeIptvResolutionBeforeActualOpen() = runTest {
        val selection = ManualSourceSelection()
        val resolver = CompletableDeferred<Unit>()
        var opens = 0
        val iptv = IptvSourcePlayback({ withContext(NonCancellable) { resolver.await() }; "https://fixture.test/iptv" }, { opens++ })
        val ticket = selection.begin()
        val pending = launch {
            try { iptv { selection.check(ticket) }; fail("Late IPTV was accepted") } catch (_: SourceUnavailable) {}
        }
        runCurrent()
        selection.begin() // viewer selects Torrentio while IPTV URL lookup is still pending
        resolver.complete(Unit)
        pending.join()
        assertEquals(0, opens)
    }
    @Test fun repeatedIptvPressesLaunchOnlyLatestResolvedRequest() = runTest {
        val selection = ManualSourceSelection()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        val oldTicket = selection.begin()
        val old = launch {
            try { IptvSourcePlayback({ withContext(NonCancellable) { first.await() }; "old" }, opened::add).invoke { selection.check(oldTicket) } }
            catch (_: SourceUnavailable) {}
        }
        runCurrent()
        val newTicket = selection.begin()
        val latest = launch { IptvSourcePlayback({ second.await(); "new" }, opened::add).invoke { selection.check(newTicket) } }
        runCurrent()
        second.complete(Unit); latest.join()
        first.complete(Unit); old.join()
        assertEquals(listOf("new"), opened)
    }
    @Test fun closeInvalidatesPendingIptvWithoutRequiringAccountOrMetadataLookup() = runTest {
        val selection = ManualSourceSelection()
        val resolver = CompletableDeferred<Unit>()
        var opened = false
        val ticket = selection.begin()
        val pending = launch {
            try { IptvSourcePlayback({ withContext(NonCancellable) { resolver.await() }; "fixture" }, { opened = true }).invoke { selection.check(ticket) } }
            catch (_: SourceUnavailable) {}
        }
        runCurrent()
        selection.cancel()
        resolver.complete(Unit); pending.join()
        assertFalse(opened)
    }
}
