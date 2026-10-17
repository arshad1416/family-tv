package app.familytv.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestSearchResultsTest {
    private data class Rows(val local: String = "", val rd: String = "")

    @Test fun localResultsPublishWhileAuthorizationGatewayIsStillPending() = runTest {
        val loader = LatestSearchResults(Rows())
        val authorization = CompletableDeferred<Unit>()
        val seen = mutableListOf<Rows>()
        val pending = launch {
            loader.load({ Rows(local = "current IPTV results") }, { rows ->
                authorization.await()
                rows.copy(rd = "authorized RD title")
            }, seen::add)
        }
        runCurrent()
        assertEquals(listOf(Rows(), Rows(local = "current IPTV results")), seen)
        authorization.complete(Unit)
        pending.join()
        assertEquals(Rows("current IPTV results", "authorized RD title"), seen.last())
    }

    @Test fun offlineGatewayKeepsCurrentLocalResultsAvailable() = runTest {
        val seen = mutableListOf<Rows>()
        LatestSearchResults(Rows()).load({ Rows(local = "offline IPTV results") }, {
            throw IOException("Portal offline")
        }, seen::add)
        assertEquals(listOf(Rows(), Rows(local = "offline IPTV results")), seen)
    }

    @Test fun olderRdCompletionCannotReplaceNewQueryEvenIfGatewayIgnoresCancellation() = runTest {
        val loader = LatestSearchResults(Rows())
        val oldGateway = CompletableDeferred<Unit>()
        val newGateway = CompletableDeferred<Unit>()
        val seen = mutableListOf<Rows>()
        val old = launch {
            loader.load({ Rows(local = "old query") }, { rows ->
                withContext(NonCancellable) { oldGateway.await() }
                rows.copy(rd = "old RD title")
            }, seen::add)
        }
        runCurrent()
        val newer = launch {
            loader.load({ Rows(local = "new query") }, { rows ->
                newGateway.await()
                rows.copy(rd = "new RD title")
            }, seen::add)
        }
        runCurrent()
        newGateway.complete(Unit)
        newer.join()
        val current = seen.toList()
        oldGateway.complete(Unit)
        old.join()
        assertEquals(current, seen)
        assertEquals(Rows("new query", "new RD title"), seen.last())
    }
}
