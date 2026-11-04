package org.example.kotlineditor.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.model.AgentSessions
import org.example.kotlineditor.model.AgentSlashCommand
import java.io.Closeable

/**
 * The agent processes of the server, one per command line, shared by the conversations that use it, and the sessions
 * they keep: the history of the conversations is the agent's, never a copy of the server's.
 */
class AgentProcesses internal constructor(
    private val scope: CoroutineScope,
    private val files: FileAccess,
    private val registry: AgentRegistry,
) : Closeable {
    private val processes = HashMap<AgentCommand, AgentProcess>()

    /** The sessions [agent] keeps that work in one of [directories], newest first. */
    suspend fun sessions(agent: AgentCommand, directories: List<String>): AgentSessions {
        val process = processFor(agent)
        if (!process.ready().sessions.list) return AgentSessions(false, emptyList())
        val found = directories.distinct().flatMap { process.listSessions(it) }
        return AgentSessions(true, found.sortedByDescending { it.updatedAt.orEmpty() })
    }

    /** Deletes the agent's [session] and its history; false when the agent cannot. */
    suspend fun delete(agent: AgentCommand, session: String): Boolean = processFor(agent).deleteSession(session)

    /** The settings (model…) a session of [agent] in [directory] offers; the agent starts for it when needed. */
    suspend fun options(agent: AgentCommand, directory: String): List<AgentOption> {
        val process = withContext(Dispatchers.IO) { processFor(agent) }
        return process.offeredOptions(directory)
    }

    /**
     * The settings a new session of [agent] has once [choices] are made (a model changes the efforts it offers), read
     * in a session started for it, then deleted; null when the agent does not run, or never started a session.
     */
    internal suspend fun preview(agent: AgentCommand, choices: Map<String, String>): List<AgentOption>? {
        val process = synchronized(processes) { processes[agent]?.takeIf { it.isAlive } }
        val directory = process?.lastDirectory ?: return null
        return process.offeredOptions(directory, choices)
    }

    /** The commands [agent] offered in its latest session, without starting it; empty before it said. */
    fun knownCommands(agent: AgentCommand): List<AgentSlashCommand> =
        synchronized(processes) { processes[agent]?.commands }.orEmpty()

    /** The settings the latest new session of [agent] offered, without starting it; empty when none started. */
    internal fun knownOptions(agent: AgentCommand): List<AgentOption> =
        synchronized(processes) { processes[agent]?.offered }.orEmpty()

    /** The agents of the ACP registry, and whether this server can run each one. Blocking. */
    fun registry(): List<AgentRegistryEntry> = registry.entries()

    /**
     * The running process of [agent], or a new one when it never ran or stopped; an agent of the registry is installed
     * first, outside the lock, so that a download never holds the other agents up. Blocking.
     */
    internal fun processFor(agent: AgentCommand): AgentProcess {
        val running = synchronized(processes) { processes[agent]?.takeIf { it.isAlive } }
        if (running != null) return running
        val id = agent.registry
        val command = if (id == null) agent else registry.command(id)
        return synchronized(processes) { processes[agent]?.takeIf { it.isAlive } ?: started(agent, command) }
    }

    // A new process of [agent], started with [command], in place of the one that stopped. Under the lock.
    private fun started(agent: AgentCommand, command: AgentCommand): AgentProcess {
        processes[agent]?.destroy()
        return AgentProcess(command, scope, files).also { processes[agent] = it }
    }

    /** Stops the processes no session uses any more. */
    internal fun destroyUnused() {
        synchronized(processes) {
            val unused = processes.filterValues { it.unused }
            unused.values.forEach(AgentProcess::destroy)
            unused.keys.forEach(processes::remove)
        }
    }

    override fun close() {
        synchronized(processes) {
            processes.values.forEach(AgentProcess::destroy)
            processes.clear()
        }
    }
}
