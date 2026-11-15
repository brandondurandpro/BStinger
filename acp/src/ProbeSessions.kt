package org.example.kotlineditor.acp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// A probe that hangs must not hold the history back for ever.
private const val PROBE_WAIT_MS = 5_000L

/**
 * The sessions an agent process starts only to read settings (`offeredOptions`), then deletes: they are no
 * conversation, and the history must not list them. The agent lists one while it lives, and the server only knows
 * its id once the agent answered, so a list taken while one is in flight is taken again once it is over.
 *
 * The sessions of the members of a team are kept here too. The agent keeps them on its disk, so their ids are saved
 * in [file], one per line: a restart of the server must not list them in the history again.
 */
internal class ProbeSessions(private val file: Path? = null) {
    private val running = MutableStateFlow(0)
    private val started = AtomicInteger()
    private val kept = ConcurrentHashMap.newKeySet<String>().apply { addAll(savedIn(file)) }

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
        if (kept.add(session)) save(session)
    }

    // A session the history must never list again; an id lost to a failed write only shows in the history.
    private fun save(session: String) {
        val target = file ?: return
        synchronized(this) {
            runCatching {
                Files.createDirectories(target.toAbsolutePath().parent)
                val line = (session + "\n").toByteArray()
                Files.write(target, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
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

// The ids saved in [file]; none when there is no file yet, or it cannot be read.
private fun savedIn(file: Path?): List<String> {
    if (file == null || !Files.exists(file)) return emptyList()
    val lines =
        try {
            Files.readAllLines(file)
        } catch (_: IOException) {
            emptyList()
        }
    return lines.map(String::trim).filter(String::isNotEmpty)
}
