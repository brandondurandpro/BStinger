package org.example.kotlineditor.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

// How long after the refresh of an IDE Git action read the branches the watch of the disk may still report what the
// action wrote in `.git` (it settles changes for 100 ms, then the client polls): a change of `.git` reported until then
// reloads the branches only once that time is past.
private const val OWN_WRITES_MILLIS = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The loads of the branches, one at a time: the loads asked meanwhile make one more. With thousands of branches, two
 * loads in parallel (a push refreshing, the watch of the disk seeing the refs it wrote) each take twice as long.
 *
 * Also tells how long the watch of the disk may still report what an IDE Git action wrote in `.git`.
 */
internal class BranchLoads(private val read: suspend () -> Unit) {
    private val mutex = Mutex()
    private val asked = AtomicLong()
    private val covered = AtomicLong()
    private val done = AtomicLong()

    // The load an IDE Git action (a fetch too) asked after it, which reads all it wrote in `.git`; the watch of the
    // disk reports those writes a little later (it settles them first), often while that load still runs.
    @Volatile
    private var actionLoad: Long? = null

    @Volatile
    private var doneAt = 0L

    /** Asks for a load now, so that a load asked later waits behind it; pass the ticket to [load]. */
    fun ask(): Long = asked.incrementAndGet()

    /** Loads the branches, unless a load that started after [ticket] was asked has read them already. */
    suspend fun load(ticket: Long = ask()) {
        mutex.withLock {
            if (covered.get() >= ticket) return
            val upTo = asked.get()
            covered.set(upTo)
            read()
            doneAt = System.nanoTime()
            done.set(upTo)
        }
    }

    /** Marks the last load asked as the one that reads what an IDE Git action wrote. */
    fun markActionLoad() {
        actionLoad = asked.get()
    }

    /** How long a change of `.git` reported now is most likely the last IDE Git action's own writes. */
    fun millisLeftOfOwnWrites(): Long {
        val ticket = actionLoad ?: return 0
        val stillReading = done.get() < ticket
        val elapsedMillis = (System.nanoTime() - doneAt) / NANOS_PER_MILLI
        return if (stillReading) OWN_WRITES_MILLIS else OWN_WRITES_MILLIS - elapsedMillis
    }
}
