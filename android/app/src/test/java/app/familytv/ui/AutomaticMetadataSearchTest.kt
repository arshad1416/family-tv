package app.familytv.ui

import app.familytv.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticMetadataSearchTest {
    private val film = DiscoveryTitle("movie", "42", "Open Film", 2024)
    @Test fun ordinaryTypingDebouncesMetadataWhileLocalRowsAreImmediatelyAvailable() = runTest {
        val seen = mutableListOf<List<String>>()
        var searches = 0
        val pending = launch {
            LatestSearchResults(emptyList<String>()).load({ listOf("IPTV") }, { local ->
                val metadata = automaticMetadataSearch("Open Film", { searches++; listOf(film) }, {}, { it })
                local + metadata.map { it.title }
            }, seen::add)
        }
        runCurrent()
        assertEquals(listOf(emptyList<String>(), listOf("IPTV")), seen)
        advanceTimeBy(349); runCurrent()
        assertEquals(0, searches)
        advanceTimeBy(1); pending.join()
        assertEquals(1, searches)
        assertEquals(listOf("IPTV", "Open Film"), seen.last())
    }
    @Test fun profileSwitchWhileMetadataWaitsCannotPublishItsLateResponse() = runTest {
        val response = CompletableDeferred<Unit>()
        var profile = "A"
        val seen = mutableListOf<List<String>>()
        val pending = launch {
            LatestSearchResults(emptyList<String>()).load({ listOf("IPTV") }, { local ->
                local + automaticMetadataSearch("Open Film", {
                    withContext(NonCancellable) { response.await() }; listOf(film)
                }, { if (profile != "A") throw SourceUnavailable("Profile changed") }, { it }).map { it.title }
            }, seen::add)
        }
        advanceTimeBy(350); runCurrent()
        profile = "B"; response.complete(Unit); pending.join()
        assertEquals(listOf("IPTV"), seen.last())
    }
    @Test fun providerErrorLeavesCatalogRowsAndDoesNotPublishMetadata() = runTest {
        val seen = mutableListOf<List<String>>()
        LatestSearchResults(emptyList<String>()).load({ listOf("IPTV") }, { local ->
            local + automaticMetadataSearch("Open Film", { throw SourceUnavailable("TMDB offline") }, {}, { it }).map { it.title }
        }, seen::add)
        assertEquals(listOf("IPTV"), seen.last())
    }
}
