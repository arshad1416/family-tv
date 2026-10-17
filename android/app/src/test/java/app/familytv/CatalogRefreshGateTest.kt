package app.familytv

import app.familytv.iptv.CatalogRefreshGate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRefreshGateTest {
    @Test fun automaticRefreshRechecksFreshnessAfterConcurrentExplicitRefreshCompletes() = runTest {
        val gate = CatalogRefreshGate()
        val pending = CompletableDeferred<Unit>()
        var stale = true
        var imports = 0
        val manual = launch { gate.run(false, { stale }) { imports++; pending.await(); stale = false } }
        runCurrent()
        val automatic = async { gate.run(true, { stale }) { imports++; stale = false } }
        runCurrent()
        assertEquals(1, imports)
        pending.complete(Unit); manual.join()
        assertFalse(automatic.await())
        assertEquals(1, imports)
        assertTrue(gate.run(false, { stale }) { imports++ })
        assertEquals(2, imports)
    }
}
