package app.familytv.ui

import app.familytv.core.ForegroundWork
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackResumeTest {
    @Test fun stopBeforeInitialLoadResumesIntendedChannelExactlyOnce() = runTest {
        val work = ForegroundWork(this)
        val resume = PlaybackResume()
        val loads = mutableListOf<CompletableDeferred<Unit>>()
        var plays = 0
        fun load() { work.launch { val ready = CompletableDeferred<Unit>(); loads += ready; ready.await(); dispatch { resume.liveLoaded(); resume.dispatched(); plays++ } } }
        fun start() = resume.resume(true, false, ::load, { error("Unexpected tune") }, { error("Unexpected VOD") }, { error("Unexpected existing media") })
        start(); runCurrent(); work.stop()
        work.start(); start(); runCurrent()
        loads[0].complete(Unit); runCurrent()
        assertEquals(0, plays)
        loads[1].complete(Unit); runCurrent()
        assertEquals(1, plays)
        work.stop()
    }
    @Test fun stopDuringTuneRestartsSelectedChannelInsteadOfOldMedia() = runTest {
        val work = ForegroundWork(this)
        val resume = PlaybackResume().apply { liveLoaded(); selected() }
        var oldMediaPlays = 0
        var selectedPlays = 0
        fun tune() { work.launch { delay(600); dispatch { resume.dispatched(); selectedPlays++ } } }
        fun start() = resume.resume(true, true, { error("Already loaded") }, ::tune, { error("Not VOD") }, { oldMediaPlays++ })
        start(); runCurrent(); work.stop()
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, selectedPlays)
        work.start(); start(); advanceTimeBy(600); runCurrent()
        assertEquals(1, selectedPlays)
        assertEquals(0, oldMediaPlays)
        work.stop()
    }
}
