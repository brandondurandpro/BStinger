package org.example.kotlineditor.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How the agents read and write the files of the projects: through the server, so the IDE sees what changed. */
interface FileAccess {
    /** The text of the file at the absolute [path]; fails when there is none. */
    fun read(path: String): String

    /** Writes [text] to the file at the absolute [path], creating it and its directories when missing. */
    fun write(path: String, text: String)

    /** The disk itself. */
    object Disk : FileAccess {
        override fun read(path: String): String =
            try {
                Files.readString(Paths.get(path))
            } catch (failure: IOException) {
                throw IOException("Cannot read $path: ${failure.message}", failure)
            }

        override fun write(path: String, text: String) {
            val file = Paths.get(path)
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, text)
        }
    }
}

/** Timings of the agents. */
data class AgentSettings(
    /** How long a transcript request waits for a change before it answers the state as it is. */
    val holdMs: Long = 20_000,
    /** How long a cancelled prompt waits for the agent to stop before it is abandoned. */
    val cancelGraceMs: Long = 5_000,
)

/**
 * The conversations of agent mode, on the machine of the sources. Each conversation has a session of an agent
 * process (one process per command line, shared by its conversations) and a [Journal] the IDE follows with
 * [transcript]: a client that reconnects asks for what it missed, and a prompt or an answer sent twice runs once.
 */
class AgentService(
    private val files: FileAccess = FileAccess.Disk,
    private val settings: AgentSettings = AgentSettings(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Instance of this service: item ids and transcript versions are only meaningful within it. */
    val epoch: String = UUID.randomUUID().toString().take(EPOCH_LENGTH)
    private val conversations = ConcurrentHashMap<String, ServerConversation>()
    private val created = MutableStateFlow(0L)
    private val processes = HashMap<AgentCommand, AgentProcess>()

    /** Sends [text] to the agent of [conversation], started with [agent] in [directory] when needed. */
    fun prompt(conversation: String, agent: AgentCommand, directory: String, itemId: String, text: String) {
        val known = conversations[conversation]
        val target =
            known ?: conversations.computeIfAbsent(conversation) { ServerConversation(Journal(epoch), scope, settings) }
        if (known == null) created.value += 1
        target.prompt(itemId, text, directory) { processFor(agent) }
    }

    /** Stops the prompt running in [conversation]. */
    fun cancel(conversation: String) {
        conversations[conversation]?.cancel()
    }

    /** Answers the permission request [itemId] of [conversation]. */
    fun answer(conversation: String, itemId: String, option: String) {
        conversations[conversation]?.answer(itemId, option)
    }

    /**
     * The items of [conversation] newer than [since], or all of them when [epoch] is not this service's, once there
     * is a change, or after [AgentSettings.holdMs].
     */
    suspend fun transcript(conversation: String, epoch: String, since: Long): AgentTranscript {
        val from = if (epoch == this.epoch) since else 0L
        val target = conversations[conversation] ?: return unknown(conversation, epoch, from)
        val journal = target.journal
        if (journal.version.value <= from) withTimeoutOrNull(settings.holdMs) { journal.version.first { it > from } }
        val snapshot = journal.since(from)
        return AgentTranscript(snapshot.version, this.epoch, true, snapshot.state, snapshot.items, snapshot.agentName)
    }

    // A conversation this service does not know (yet): said at once to a client of another epoch, after a while (or
    // as soon as it starts) to one that already knows.
    private suspend fun unknown(conversation: String, epoch: String, from: Long): AgentTranscript {
        if (epoch == this.epoch) {
            withTimeoutOrNull(settings.holdMs) { created.first { conversations.containsKey(conversation) } }
            if (conversations.containsKey(conversation)) return transcript(conversation, epoch, from)
        }
        return AgentTranscript(0, this.epoch, false, AgentTurnState.Idle, emptyList())
    }

    /** Forgets [conversation]: its prompt stops and its agent process ends once no conversation uses it. */
    fun close(conversation: String) {
        val removed = conversations.remove(conversation) ?: return
        removed.close()
        synchronized(processes) {
            val unused = processes.filterValues { it.unused }
            unused.values.forEach(AgentProcess::destroy)
            unused.keys.forEach(processes::remove)
        }
    }

    override fun close() {
        conversations.values.forEach(ServerConversation::close)
        conversations.clear()
        synchronized(processes) {
            processes.values.forEach(AgentProcess::destroy)
            processes.clear()
        }
        scope.cancel()
    }

    // The running process of [agent], or a new one when it never ran or stopped.
    private fun processFor(agent: AgentCommand): AgentProcess =
        synchronized(processes) {
            val known = processes[agent]
            if (known != null && known.isAlive) return known
            known?.destroy()
            AgentProcess(agent, scope, files).also { processes[agent] = it }
        }

    private companion object {
        const val EPOCH_LENGTH = 8
    }
}
