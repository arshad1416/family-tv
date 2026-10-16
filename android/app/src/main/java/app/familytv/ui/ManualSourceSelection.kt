package app.familytv.ui

import app.familytv.discovery.SourceUnavailable
import java.util.concurrent.atomic.AtomicLong

/** Synchronous invalidation also rejects providers that complete after coroutine cancellation. */
internal class ManualSourceSelection {
    private val generation = AtomicLong()
    fun begin(): Long = generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun check(ticket: Long) {
        if (ticket != generation.get()) throw SourceUnavailable("The source selection changed. Choose a release again.")
    }
}
