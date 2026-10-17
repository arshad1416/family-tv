package app.familytv

import app.familytv.sync.CommandLedger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class CommandLedgerTest {
    @Test fun cancelledAcknowledgementRetriesOnlyAckAndNeverRepeatsCompletedAction() = runTest {
        val ledger = CommandLedger()
        var actions = 0
        var acknowledgements = 0
        try { ledger.run("synthetic", { actions++; "completed" }) { acknowledgements++; throw CancellationException("Stopped") } }
        catch (_: CancellationException) {}
        ledger.run("synthetic", { actions++; "repeated" }) { result -> acknowledgements++; assertEquals("completed", result) }
        assertEquals(1, actions)
        assertEquals(2, acknowledgements)
    }
    @Test fun cancelledActionCanBeRetriedAndNeverAcknowledgesIncompleteWork() = runTest {
        val ledger = CommandLedger()
        var actions = 0
        var acknowledgements = 0
        try { ledger.run("synthetic", { actions++; throw CancellationException("Stopped") }) { acknowledgements++ } }
        catch (_: CancellationException) {}
        ledger.run("synthetic", { actions++; "completed" }) { acknowledgements++ }
        assertEquals(2, actions)
        assertEquals(1, acknowledgements)
    }
}
