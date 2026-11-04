package org.example.kotlineditor.model

// Agent mode: conversations with coding agents (Claude Code, Codex, Gemini CLI…) that the server runs through the
// Agent Client Protocol, next to the sources. The IDE never sees ACP: it follows a transcript the server keeps.

/**
 * How the server starts an agent: its command line and the variables added to its environment, or the agent of the
 * ACP registry [registry] names, which the server installs and starts its own way.
 */
data class AgentCommand(
    /** Program and arguments, such as `npx -y @agentclientprotocol/claude-agent-acp`; empty for [registry]. */
    val command: List<String>,
    /** Variables added to the environment of the agent (an API key). */
    val environment: Map<String, String> = emptyMap(),
    /** Id of an agent of the ACP registry (`claude-acp`), installed by the server; null for [command]. */
    val registry: String? = null,
)

/** An agent of the ACP registry, as the server can run it. */
data class AgentRegistryEntry(
    /** Its id (`claude-acp`). */
    val id: String,
    /** Its name, for people (`Claude Agent`). */
    val name: String,
    /** The version the server installs. */
    val version: String,
    /** What it is. */
    val description: String,
    /** Why the server cannot run it (no build for its platform, no checksum, no Node.js…); null when it can. */
    val unavailable: String? = null,
)

/** What the agent of a conversation is doing. */
enum class AgentTurnState {
    /** Waiting for a prompt. */
    Idle,

    /** Starting the agent or its session. */
    Starting,

    /** Answering a prompt. */
    Running,
}

/** What a tool call does, from the agent: it picks the icon and how the call is shown. */
enum class AgentToolKind {
    /** Reads files. */
    Read,

    /** Changes files. */
    Edit,

    /** Deletes files. */
    Delete,

    /** Moves or renames files. */
    Move,

    /** Searches the project. */
    Search,

    /** Runs a command. */
    Execute,

    /** Thinks or plans, without touching anything. */
    Think,

    /** Fetches from the network. */
    Fetch,

    /** Anything else. */
    Other,
}

/** Progress of a tool call. */
enum class AgentToolStatus {
    /** Not started: waiting for its input or for a permission. */
    Pending,

    /** Running. */
    Running,

    /** Done. */
    Completed,

    /** Failed, refused or stopped. */
    Failed,
}

/** What a tool call shows of a command the agent runs: the end of its output, and its exit code once it exited. */
data class AgentTerminalOutput(
    /** The end of its output, stdout and stderr mixed as they came. */
    val text: String,
    /** Its exit code; null while it runs. */
    val exitCode: Int? = null,
    /** Whether the beginning of the output is not shown. */
    val truncated: Boolean = false,
)

/** A change of a file proposed or made by a tool call: the whole old text (null for a new file) and new text. */
data class AgentDiff(
    /** Absolute path of the file. */
    val path: String,
    /** Text before the change, null when the file is new. */
    val oldText: String?,
    /** Text after the change. */
    val newText: String,
)

/** A step of the plan of the agent. */
data class AgentPlanEntry(
    /** What the step does. */
    val content: String,
    /** `pending`, `in_progress` or `completed`. */
    val status: String,
)

/** A choice offered by a permission request: allow once, allow always, reject. */
data class AgentPermissionOption(
    /** Id the agent gave the option. */
    val id: String,
    /** Label of the button. */
    val name: String,
    /** `allow_once`, `allow_always`, `reject_once` or `reject_always`. */
    val kind: String,
)

/**
 * One entry of a transcript, which a client shows as it is. [id] never changes and is unique across restarts of the
 * server; [version] is the version of the transcript when the item last changed, so a client asks only for the items
 * newer than its own.
 */
sealed interface AgentItem {
    /** Identity of the item: a newer version of it replaces the one shown. */
    val id: String

    /** Version of the transcript when the item last changed; 0 for an item the client added itself. */
    val version: Long

    /** A prompt of the user. */
    data class UserText(
        override val id: String,
        override val version: Long,
        /** What the user typed. */
        val text: String,
        /** What the user joined to it, as the prompt shows it ([AgentAttachment.label]). */
        val context: List<String> = emptyList(),
    ) : AgentItem

    /** Text of the agent, streamed: it grows until another item starts. */
    data class AgentText(
        override val id: String,
        override val version: Long,
        /** The text received so far. */
        val text: String,
    ) : AgentItem

    /** What the agent thinks before it answers, streamed like [AgentText]. */
    data class Thought(
        override val id: String,
        override val version: Long,
        /** The thought received so far. */
        val text: String,
    ) : AgentItem

    /** An action of the agent on the project (read a file, edit it, run a command), shown as a card. */
    data class ToolCall(
        override val id: String,
        override val version: Long,
        /** What it does, for people (`Read README.md`). */
        val title: String,
        /** What kind of action it is. */
        val kind: AgentToolKind,
        /** How far it got. */
        val status: AgentToolStatus,
        /** Text the tool produced (its output, or why it failed). */
        val output: String = "",
        /** Files it changes. */
        val diffs: List<AgentDiff> = emptyList(),
        /** Files it works on, absolute. */
        val locations: List<String> = emptyList(),
        /** The command it runs on the server, when it runs one. */
        val terminal: AgentTerminalOutput? = null,
    ) : AgentItem

    /** The plan of the agent for the current prompt, replaced as it goes. */
    data class Plan(
        override val id: String,
        override val version: Long,
        /** Its steps, in order. */
        val entries: List<AgentPlanEntry>,
    ) : AgentItem

    /**
     * The agent asks before it runs a tool call. [chosen] is the option answered, null while it waits; a request
     * left without an answer when the prompt is cancelled or the agent stops is [cancelled].
     */
    data class Permission(
        override val id: String,
        override val version: Long,
        /** The tool call it asks for. */
        val title: String,
        /** The answers offered. */
        val options: List<AgentPermissionOption>,
        val chosen: String? = null,
        val cancelled: Boolean = false,
    ) : AgentItem {
        /** Whether it still waits for the user. */
        val pending: Boolean get() = chosen == null && !cancelled
    }

    /** Something the IDE says about the conversation: it was stopped, the agent failed, a new session started. */
    data class Notice(
        override val id: String,
        override val version: Long,
        /** What happened. */
        val text: String,
        /** Whether it went wrong (shown in red). */
        val error: Boolean,
    ) : AgentItem
}

private const val NO_SERVER = "No server runs the agents"

/**
 * Something the user joins to a prompt, such as a file of the project, lines of one or an image.
 *
 * A path is relative to the directory the agent works in, with `/`: the same file in the project and in a worktree.
 */
sealed interface AgentAttachment {
    /** What the prompt shows of it, such as a file name or `Main.kt:3-7`. */
    val label: String

    /** A file, sent whole when the agent takes it, else as a link the agent reads. */
    data class File(
        /** Where it is. */
        val path: String,
    ) : AgentAttachment {
        override val label: String get() = path.substringAfterLast('/')
    }

    /** Lines [first] to [last] of the file at [path] (from 1), whose text is [text]. */
    data class Lines(val path: String, val first: Int, val last: Int, val text: String) : AgentAttachment {
        override val label: String
            get() {
                val name = path.substringAfterLast('/')
                return if (first == last) "$name:$first" else "$name:$first-$last"
            }
    }

    /** An image pasted in the prompt, [data] being its bytes in Base64. */
    data class Image(
        /** What the prompt calls it (`image-1.png`). */
        val name: String,
        /** Its type (`image/png`). */
        val mimeType: String,
        val data: String,
    ) : AgentAttachment {
        override val label: String get() = name
    }
}

/** A command the agent offers, typed in the prompt as `/name`, such as `/compact` or `/review`. */
data class AgentSlashCommand(
    /** Its name, without the slash. */
    val name: String,
    /** What it does. */
    val description: String,
    /** What to type after it, when it takes something (`text to echo`); null when it takes nothing. */
    val hint: String? = null,
)

/** A value an [AgentOption] can take: a model of the agent, or a mode. */
data class AgentOptionValue(
    /** What the agent is told when it is chosen. */
    val value: String,
    /** What the user reads. */
    val name: String,
    /** What it is good at, when the agent says. */
    val description: String? = null,
)

/**
 * A setting of a session the agent offers, among values it lists: its model, its mode, how hard it thinks (the
 * `select` session config options of ACP).
 */
data class AgentOption(
    /** What the agent names it, to change it. */
    val id: String,
    /** What the user reads (`Model`). */
    val name: String,
    /** What it is about, as ACP names it (`model`, `mode`, `thought_level`…); null when the agent does not say. */
    val category: String?,
    /** The value chosen. */
    val current: String,
    /** The values, in the order the agent prefers. */
    val values: List<AgentOptionValue>,
) {
    /** The ACP categories the IDE knows. */
    companion object {
        /** The category of the model of the session. */
        const val MODEL = "model"

        /** The category of the mode of the session: for Claude, its permissions (ask, accept edits, plan…). */
        const val MODE = "mode"

        /** The category of how hard the agent thinks: for Claude, its effort, which depends on the model. */
        const val THOUGHT_LEVEL = "thought_level"
    }
}

/**
 * The items of a conversation newer than what the client had, and its state. [known] is false when the server does
 * not know the conversation (it restarted since): the client keeps what it shows, and the next prompt starts a new
 * session of the agent.
 */
data class AgentTranscript(
    /** Version of the latest change, to ask for the next ones. */
    val version: Long,
    /** Instance of the server: a client that sees another one asks for everything again. */
    val epoch: String,
    /** Whether the server knows the conversation. */
    val known: Boolean,
    /** What the agent is doing. */
    val state: AgentTurnState,
    /** Items that changed since the version asked for, oldest first. */
    val items: List<AgentItem>,
    /** Name of the agent and its version, as it introduced itself; null before it started. */
    val agentName: String? = null,
    /** Id of the session of the agent, kept by the agent itself: a conversation reopens it after a restart. */
    val session: String? = null,
    /** Title of the session, when the agent gave one. */
    val title: String? = null,
    /** Whether the items start with the history of the session the agent replayed: they replace those shown. */
    val replayed: Boolean = false,
    /** The settings of the session (its model…), as chosen; empty before the agent said which it offers. */
    val options: List<AgentOption> = emptyList(),
    /** The commands the agent offers in this session; empty before it said. */
    val commands: List<AgentSlashCommand> = emptyList(),
)

/** A session an agent keeps, as the agent lists it: the history of a conversation. */
data class AgentSession(
    /** Its id for the agent. */
    val id: String,
    /** Directory the agent worked in. */
    val directory: String,
    /** Its title, when the agent gave one. */
    val title: String? = null,
    /** When it last changed (ISO 8601), when the agent said. */
    val updatedAt: String? = null,
    /** Branch of the worktree of agent mode it worked in; null for the project itself. */
    val branch: String? = null,
)

/** The sessions an agent keeps for a project. */
data class AgentSessions(
    /** False when the agent does not list its sessions. */
    val supported: Boolean,
    /** Its sessions, newest first. */
    val sessions: List<AgentSession>,
)

/** What the user chooses of the agents: which agent (from the ACP registry), and the settings of its sessions. */
interface AgentChoices {
    /** The agents of the ACP registry, and whether the server can run each one. */
    suspend fun registry(): List<AgentRegistryEntry>

    /**
     * The settings a session of [agent] working in [directory] offers (its model…), before a conversation started one;
     * empty when the agent offers none. The agent may start for it.
     */
    suspend fun options(agent: AgentCommand, directory: String): List<AgentOption>

    /**
     * Sets the setting [option] of the session of [conversation] to [value]: at once when the session runs, as soon
     * as it starts otherwise. The transcript shows the settings as the agent then says they are.
     */
    suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String)

    /** The commands [agent] offered in its latest session, without starting it; empty before it said. */
    suspend fun commands(agent: AgentCommand): List<AgentSlashCommand>
}

/** Conversations with agents, run by the server. Every call returns at once except [transcript], a long poll. */
interface Agents : AgentChoices {
    /**
     * Sends [text] to the agent of [conversation], started with [agent] in [directory] if it does not run yet. The
     * prompt shows as item [itemId]. A conversation the server does not know continues the agent's [session], when
     * the agent can reopen it. [attachments] go with the text. A request sent twice is run once.
     */
    suspend fun prompt(
        conversation: String,
        agent: AgentCommand,
        directory: String,
        itemId: String,
        text: String,
        session: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
    )

    /**
     * Opens the agent's [session] (from [sessions]) as [conversation], working in [directory]: its transcript replays
     * the history the agent kept. A request sent twice is run once.
     */
    suspend fun open(conversation: String, agent: AgentCommand, directory: String, session: String)

    /** The sessions [agent] keeps for the project at [root] and its worktrees of agent mode, newest first. */
    suspend fun sessions(agent: AgentCommand, root: String): AgentSessions

    /** Deletes the agent's [session] and its history, when the agent can; returns whether it did. */
    suspend fun delete(agent: AgentCommand, session: String): Boolean

    /** Stops the prompt running in [conversation]; its pending permission requests are cancelled. */
    suspend fun cancel(conversation: String)

    /** Answers the permission request [itemId] of [conversation] with [option]. */
    suspend fun answer(conversation: String, itemId: String, option: String)

    /**
     * The items of [conversation] newer than [since] (every item when [epoch] is not the server's), once there is
     * one, or the state after a while.
     */
    suspend fun transcript(conversation: String, epoch: String, since: Long): AgentTranscript

    /** Forgets [conversation] and ends its session. */
    suspend fun close(conversation: String)

    /** No agents (an IDE without a server). */
    object None : Agents, AgentChoices by NoChoices {
        override suspend fun prompt(
            conversation: String,
            agent: AgentCommand,
            directory: String,
            itemId: String,
            text: String,
            session: String?,
            attachments: List<AgentAttachment>,
        ): Unit = throw java.io.IOException(NO_SERVER)

        override suspend fun open(conversation: String, agent: AgentCommand, directory: String, session: String): Unit =
            throw java.io.IOException(NO_SERVER)

        override suspend fun sessions(agent: AgentCommand, root: String) = AgentSessions(false, emptyList())

        override suspend fun delete(agent: AgentCommand, session: String) = false

        override suspend fun cancel(conversation: String) = Unit

        override suspend fun answer(conversation: String, itemId: String, option: String) = Unit

        override suspend fun transcript(conversation: String, epoch: String, since: Long): AgentTranscript =
            kotlinx.coroutines.awaitCancellation()

        override suspend fun close(conversation: String) = Unit
    }
}

// The choices of an IDE without a server: none.
private object NoChoices : AgentChoices {
    override suspend fun registry(): List<AgentRegistryEntry> = emptyList()

    override suspend fun options(agent: AgentCommand, directory: String): List<AgentOption> = emptyList()

    override suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String): Unit =
        throw java.io.IOException(NO_SERVER)

    override suspend fun commands(agent: AgentCommand): List<AgentSlashCommand> = emptyList()
}
