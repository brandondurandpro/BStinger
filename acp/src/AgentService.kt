package org.example.kotlineditor.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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

/** Timings of the agents, and where the agents of the ACP registry come from. */
data class AgentSettings(
    /** How long a transcript request waits for a change before it answers the state as it is. */
    val holdMs: Long = 20_000,
    /** How long a cancelled prompt waits for the agent to stop before it is abandoned. */
    val cancelGraceMs: Long = 5_000,
    /** The ACP registry of agents. */
    val registryUrl: String = REGISTRY_URL,
    /** How long the registry is used before it is read again. */
    val registryCacheMs: Long = 3_600_000,
    /** Where the agents of the registry are installed, shared with the other downloaded tools. */
    val toolsDirectory: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", "tools"),
) {
    /** The registry the server reads by default. */
    companion object {
        /** The registry of agentclientprotocol.com. */
        const val REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
    }
}

/**
 * The conversations of agent mode, on the machine of the sources. Each conversation has a session of an agent
 * process (one process per command line, shared by its conversations) and a [Journal] the IDE follows with
 * [transcript]: a client that reconnects asks for what it missed, and a prompt or an answer sent twice runs once.
 */
@Suppress("TooManyFunctions") // the conversations of agent mode: prompt, follow, cancel, answer, and what a team needs
class AgentService(
    private val files: FileAccess = FileAccess.Disk,
    private val settings: AgentSettings = AgentSettings(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Instance of this service: item ids and transcript versions are only meaningful within it. */
    val epoch: String = UUID.randomUUID().toString().take(EPOCH_LENGTH)
    private val conversations = ConcurrentHashMap<String, ServerConversation>()
    private val created = MutableStateFlow(0L)

    /** The agent processes, one per command line, and the sessions they keep. */
    val processes = AgentProcesses(scope, files, registry(settings))

    /**
     * Sends [text] to the agent of [conversation], started with [agent] in [directory] when needed; a conversation
     * this service does not know continues the agent's [session]; [attachments] go with the text.
     *
     * The transcript shows [shown] as the user's message [itemId]: the text sent by default, another one when the
     * agent receives more than the user typed (a preamble), none at all when [shown] is null (a relay the user did
     * not write). [itemId] identifies the prompt either way: sent twice, it runs once, and [awaitTurn] finds it.
     * The holder of a [lease] on [conversation] passes it, so that its prompts do not wait behind the ones others
     * sent during the lease.
     */
    fun prompt(
        conversation: String,
        agent: AgentCommand,
        directory: String,
        itemId: String,
        text: String,
        session: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        shown: String? = text,
        lease: ConversationLease? = null,
    ) {
        val prompt = UserPrompt(itemId, text, attachments, shown)
        val target = conversation(conversation)
        target.prompt(prompt, agent, directory, session, { processes.processFor(agent) }, lease)
    }

    /**
     * Waits for the end of the turn the prompt [itemId] started in [conversation] (after it left the queue, when it
     * waited there), and tells how it ended and what the agent answered. A conversation closed meanwhile (or already
     * forgotten) ends cancelled. Fails for an [itemId] [prompt] never accepted in [conversation].
     */
    suspend fun awaitTurn(conversation: String, itemId: String): TurnOutcome {
        val target = conversations[conversation]
        return target?.awaitTurn(itemId) ?: TurnOutcome(TurnEnding.Cancelled, "", "Unknown conversation.")
    }

    /**
     * Adds to the transcript of [conversation] the item [create] builds from its new id (its version is the journal's,
     * whatever [create] puts there); the clients following it receive the item like any other. Returns the item.
     */
    fun addItem(conversation: String, create: (id: String) -> AgentItem): AgentItem {
        val journal = conversation(conversation).journal
        val id = journal.newId()
        return journal.add { version -> create(id).withVersion(version) }
    }

    /**
     * Replaces the item [id] of [conversation] by what [change] makes of it, as a new version the clients receive;
     * false when there is no such item.
     */
    fun updateItem(conversation: String, id: String, change: (AgentItem) -> AgentItem): Boolean {
        val journal = conversations[conversation]?.journal ?: return false
        return journal.update(id) { item, version -> change(item).withVersion(version) }
    }

    /**
     * Keeps [conversation] busy from now until the returned lease is released, also between its turns: its transcript
     * never says idle meanwhile, so that a client keeps showing one run, however long only other conversations work.
     * Cancelling or closing the conversation completes [ConversationLease.stopped]. Prompts of others during the lease
     * wait in the queue of the conversation.
     */
    fun keepBusy(conversation: String): ConversationLease = conversation(conversation).keepBusy()

    /** Opens the agent's [session] as [conversation]: the agent replays its history into the transcript. */
    fun open(conversation: String, agent: AgentCommand, directory: String, session: String) {
        conversation(conversation).open(agent, session, directory) { processes.processFor(agent) }
    }

    /**
     * Sets the setting [option] (the model…) of the session of [conversation] to [value], now or as soon as it starts:
     * meanwhile its transcript shows the settings the sessions of [agent] offer, with the choice made.
     */
    suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String) {
        val preview = Preview { choices -> processes.preview(agent, choices) }
        conversation(conversation).keeper.setOption(option, value, processes.knownOptions(agent), preview)
    }

    /** Stops the prompt running in [conversation]. */
    fun cancel(conversation: String) {
        conversations[conversation]?.cancel()
    }

    /**
     * Asks the user [question] in [conversation], with the [choices] proposed, and waits: the choice or the words of
     * the user come back, null when the conversation is stopped or closed meanwhile. The question shows in the
     * transcript like a permission request and is answered like one ([answer]); nothing of the agent runs meanwhile.
     */
    suspend fun ask(conversation: String, question: String, choices: List<String>): String? =
        conversation(conversation).ask(question, choices)

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
        val header = snapshot.header
        return AgentTranscript(
            version = snapshot.version,
            epoch = this.epoch,
            known = true,
            state = header.state,
            items = snapshot.items,
            agentName = header.agentName,
            session = header.session,
            title = header.title,
            replayed = header.replayed,
            replayable = header.replayable,
            options = header.options,
            commands = header.commands,
            usage = header.usage,
        )
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
    suspend fun close(conversation: String) {
        val removed = conversations.remove(conversation) ?: return
        removed.close()
        processes.destroyUnused()
    }

    override fun close() {
        conversations.clear()
        processes.close()
        scope.cancel()
    }

    // The conversation [key], created on first use.
    private fun conversation(key: String): ServerConversation {
        val known = conversations[key]
        if (known != null) return known
        val target = conversations.computeIfAbsent(key) { ServerConversation(Journal(epoch), scope, settings) }
        created.value += 1
        return target
    }

    private companion object {
        const val EPOCH_LENGTH = 8
    }
}

// A coding agent can weigh several hundred MB (an Electron one more): the cap only guards against a runaway download.
private const val MAX_AGENT_DOWNLOAD = 4L * 1024 * 1024 * 1024

private fun registry(settings: AgentSettings): AgentRegistry {
    val installer = AgentInstaller(ToolDownloader(settings.toolsDirectory, maxBytes = MAX_AGENT_DOWNLOAD))
    return AgentRegistry(settings.registryUrl, installer, settings.registryCacheMs)
}
