package app.familytv.ui

import app.familytv.core.*
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWorkTest {
    @Test fun stopCancelsPendingRetryAndRejectsLateNoncooperativePlayback() = runTest {
        val work = ForegroundWork(this)
        val pending = CompletableDeferred<Unit>()
        var opens = 0
        val job = work.launch { withContext(NonCancellable) { pending.await(); dispatch { opens++ } } }
        runCurrent()
        work.stop()
        work.start() // An old callback must still be rejected after the Activity returns.
        pending.complete(Unit)
        job.join()
        assertEquals(0, opens)
        work.stop()
        var hiddenRequests = 0
        work.launch { hiddenRequests++ }.join()
        assertEquals(0, hiddenRequests)
        work.start()
        work.launch { dispatch { opens++ } }.join()
        assertEquals(1, opens)
        work.stop()
    }

    @Test fun stopCancelsScheduledRetryBeforeItDoesAnyWork() = runTest {
        val work = ForegroundWork(this)
        var requests = 0
        work.launch { delay(1500); requests++ }
        runCurrent()
        work.stop()
        advanceTimeBy(5000); runCurrent()
        assertEquals(0, requests)
    }

    @Test fun timerProducesNoTicksWhileHiddenAndResumesOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner.registry.currentState = Lifecycle.State.CREATED
            var ticks = 0
            val timer = backgroundScope.launch { owner.lifecycle.foregroundTicks(800) { ticks++ } }
            runCurrent(); advanceTimeBy(2400); runCurrent()
            assertEquals(0, ticks)
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, ticks)
            advanceTimeBy(800); runCurrent()
            assertEquals(2, ticks)
            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent(); advanceTimeBy(8000); runCurrent()
            assertEquals(2, ticks)
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(3, ticks)
            timer.cancel()
        } finally { Dispatchers.resetMain() }
    }
}
