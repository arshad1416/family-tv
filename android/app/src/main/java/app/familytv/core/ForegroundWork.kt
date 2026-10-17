package app.familytv.core

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

/** STOP cancels all child work; generation checks also reject late noncooperative callbacks. */
internal class ForegroundWork(private val scope: CoroutineScope, initiallyActive: Boolean = true) {
    var active = initiallyActive
        private set
    private var generation = 0L
    private var jobs = SupervisorJob(scope.coroutineContext[Job]).apply { if (!initiallyActive) cancel() }
    /** Each launch reads the current lifecycle group, including after a STOP / START cycle. */
    val coroutineScope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: kotlin.coroutines.CoroutineContext get() = scope.coroutineContext + jobs
    }
    fun start() {
        if (active) return
        jobs = SupervisorJob(scope.coroutineContext[Job])
        active = true
    }
    fun stop() { active = false; generation++; jobs.cancel() }
    inner class Task internal constructor(private val ticket: Long, private val context: kotlin.coroutines.CoroutineContext) {
        fun ensureCurrent() {
            if (!active || ticket != generation || !context.isActive) throw CancellationException("Foreground request ended")
        }
        fun dispatch(block: () -> Unit) {
            if (active && ticket == generation && context.isActive) block()
        }
    }
    fun launch(block: suspend Task.() -> Unit): Job {
        if (!active) return Job().apply { cancel() }
        val ticket = generation
        return scope.launch(jobs) { Task(ticket, currentCoroutineContext()).block() }
    }
    fun dispatch(block: () -> Unit) { if (active) block() }
}

/** Retained compositions must not keep wall-clock timers running after their Activity stops. */
internal suspend fun Lifecycle.foregroundTicks(intervalMs: Long, tick: () -> Unit) {
    require(intervalMs > 0)
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { tick(); delay(intervalMs) }
    }
}
