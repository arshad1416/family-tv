package app.familytv

import app.familytv.trailer.LookupGate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LookupGateTest {
    @Test fun nestedLookupReusesThePermitInsteadOfDeadlocking() = runBlocking {
        val gate = LookupGate(1)
        val v = withTimeout(2_000) { gate.run { gate.run { "inner" } } }
        assertEquals("inner", v)
    }

    @Test fun lookupsRunOneAfterAnother() = runBlocking {
        val gate = LookupGate(1)
        assertEquals(listOf(1, 2), listOf(gate.run { 1 }, gate.run { 2 }))
    }
}
