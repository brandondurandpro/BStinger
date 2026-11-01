package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.lsp.leak.LeakDetector
import org.example.kotlineditor.lsp.leak.LeakSettings
import org.example.kotlineditor.lsp.leak.MemoryPoint
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.model.LanguageServerInfo
import java.io.Closeable

/** A language server wanted for a root directory. */
data class ServerTarget(
    /** What to run. */
    val spec: LanguageServerSpec,
    /** Directory it serves. */
    val root: String,
) {
    /** Id of the server: stable across its restarts. */
    val id: String get() = "${spec.name}@$root"
}

/**
 * Runs the language servers the IDE sessions need: one process per [ServerTarget], shared by every session that
 * uses it, stopped [SupervisorTimings.idleStopMs] after its last user left (see #26, "Process lifecycle").
 *
 * Each server is driven by one coroutine that handles its commands in order (start, restart, stop, crash...), so
 * that a restart clicked twice, a crash during a stop or a stop during a start never leave two processes or none.
 * A crashed server restarts after 1 s, 2 s, 4 s..., and stays [LanguageServerState.Crashed] after
 * [SupervisorTimings.maxCrashes] crashes in [SupervisorTimings.crashWindowMs]. Stopping asks `shutdown`, sends
 * `exit`, then kills the whole process tree.
 *
 * [onChange] is called after every change of what [servers] returns, except memory, which the caller samples.
 */
@Suppress("TooManyFunctions")
class LanguageServerSupervisor(
    private val scope: CoroutineScope,
    // Samples the memory of each server's process tree; null when memory is not shown.
    private val sampler: ProcessMemorySampler? = null,
    // Records the processes so that the next IDE server stops them after a crash of this one.
    private val reaper: OrphanReaper? = null,
    private val timings: SupervisorTimings = SupervisorTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val leakSettings: LeakSettings = LeakSettings(),
    private val onChange: () -> Unit = {},
) : Closeable {
    private val lock = Any()
    private val managed = LinkedHashMap<String, ManagedServer>()
    private val uses = HashMap<String, Set<String>>()

    init {
        reaper?.reap()
        scope.launch { checkLeaks() }
    }

    /**
     * Sets the servers [user] (an IDE session) needs, replacing what it needed before: new ones start, the ones
     * nobody needs any more stop after the idle delay.
     */
    fun use(user: String, targets: Collection<ServerTarget>) {
        synchronized(lock) {
            val wanted = targets.associateBy { it.id }
            val before = uses[user].orEmpty()
            if (wanted.isEmpty()) uses.remove(user) else uses[user] = wanted.keys
            for ((id, target) in wanted) {
                val server = managed.getOrPut(id) { newServer(target) }
                if (server.users.add(user)) server.send(Command.Wanted)
            }
            for (id in before - wanted.keys) {
                val server = managed[id] ?: continue
                server.users.remove(user)
                if (server.users.isEmpty()) server.send(Command.Unused)
            }
        }
        onChange()
    }

    /** [user] is gone (its session ended): like [use] with nothing. */
    fun release(user: String) = use(user, emptyList())

    /** Restarts [id] if it still runs [generation]; true when the restart was accepted. */
    fun restart(id: String, generation: Long): Boolean {
        val server = synchronized(lock) { managed[id] } ?: return false
        server.send(Command.Restart(generation))
        return true
    }

    /** Stops [id] until [start]. */
    fun stop(id: String) {
        synchronized(lock) { managed[id] }?.send(Command.Stop)
    }

    /** Starts [id] after a stop, a crash or an unavailability. */
    fun start(id: String) {
        synchronized(lock) { managed[id] }?.send(Command.Start)
    }

    /** The log of [id], oldest line first; empty for an unknown server. */
    fun log(id: String): List<String> = synchronized(lock) { managed[id] }?.log?.lines().orEmpty()

    /** Every server, in the order they were first needed. */
    fun servers(): List<LanguageServerInfo> = synchronized(lock) { managed.values.toList() }.map { it.info() }

    /** The pid of [id]'s process while it runs (tests). */
    fun pidOf(id: String): Long? = synchronized(lock) { managed[id] }?.pid

    /** Stops every server: `shutdown` and `exit` for all of them at once, then the remaining processes are killed. */
    override fun close() {
        val all = synchronized(lock) { managed.values.toList().also { managed.clear() } }
        runBlocking(Dispatchers.IO) {
            // Each server waits for shutdown, then for its exit: twice the timeout, and a margin.
            withTimeoutOrNull(timings.shutdownTimeoutMs * 2 + CLOSE_MARGIN_MS) {
                val closed = all.map { server -> CompletableDeferred<Unit>().also { server.send(Command.Close(it)) } }
                closed.awaitAll()
            }
        }
        all.forEach { it.kill() }
    }

    /** Stops now the servers of [language] that nobody uses and that are not [keep] (another server was chosen). */
    fun discard(language: String, keep: String) {
        val retired =
            synchronized(lock) {
                managed.values.filter { it.language == language && it.name != keep && it.users.isEmpty() }
            }
        retired.forEach { it.send(Command.Discard) }
    }

    private fun newServer(target: ServerTarget) =
        ManagedServer(target, scope, sampler, reaper, timings, clock, ::forget, onChange)

    private fun forget(server: ManagedServer): Boolean {
        val removed =
            synchronized(lock) {
                (managed[server.id] === server && server.users.isEmpty()).also { if (it) managed.remove(server.id) }
            }
        if (removed) onChange()
        return removed
    }

    private suspend fun checkLeaks() {
        while (scope.isActive) {
            delay(timings.leakCheckMs)
            val now = clock()
            var changed = false
            for (server in synchronized(lock) { managed.values.toList() }) {
                val history = server.history ?: continue
                val points = history.snapshot().map { MemoryPoint(it.timeMillis, it.privateBytes) }
                // No activity yet: requests and edits arrive with the document sync (#96).
                val report =
                    LeakDetector.evaluate(
                        points,
                        emptyList(),
                        server.indexingEndedAt,
                        server.startedAt,
                        now,
                        leakSettings,
                    )
                changed = server.setLeak(report) || changed
            }
            if (changed) onChange()
        }
    }

    private companion object {
        const val CLOSE_MARGIN_MS = 1_000L
    }
}
