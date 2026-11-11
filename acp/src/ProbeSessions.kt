package org.example.kotlineditor.acp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// A probe that hangs must not hold the history back for ever.
private const val PROBE_WAIT_MS = 5_000L

/**
 * The sessions an agent process starts only to read settings (`offeredOptions`), then deletes: they are no
 * conversation, and the history must not list them. The agent lists one while it lives, and the server only knows
 * its id once the agent answered, so a list taken while one is in flight is taken again once it is over.
 */
internal class ProbeSessions {
    private val running = MutableStateFlow(0)
    private val started = AtomicInteger()
    private val kept = ConcurrentHashMap.newKeySet<String>()

    /** Runs [block], which starts and ends a probe session. */
    suspend fun <T> probing(block: suspend () -> T): T {
        started.incrementAndGet()
        running.update { it + 1 }
        try {
            return block()
        } finally {
            running.update { it - 1 }
        }
    }

    /** The probe [session] could not be deleted: the agent goes on listing it. */
    fun keep(session: String) {
        kept.add(session)
    }

    /** Whether [session] is a probe the agent still lists. */
    fun isKept(session: String): Boolean = session in kept

    /** What [overlapped] compares: taken before a list is asked. */
    fun mark(): Mark = Mark(started.get(), running.value > 0)

    /** Whether a probe lived at some point since [mark]. */
    fun overlapped(mark: Mark): Boolean = mark.running || started.get() != mark.started

    /** Waits until no probe is in flight, for a while. */
    suspend fun awaitNone() {
        withTimeoutOrNull(PROBE_WAIT_MS) { running.first { it == 0 } }
    }

    class Mark(val started: Int, val running: Boolean)
}
