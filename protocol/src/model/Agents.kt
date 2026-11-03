package org.example.kotlineditor.model

// Agent mode: conversations with coding agents (Claude Code, Codex, Gemini CLI…) that the server runs through the
// Agent Client Protocol, next to the sources. The IDE never sees ACP: it follows a transcript the server keeps.

/** How the server starts an agent: its command line and the variables added to its environment. */
data class AgentCommand(
    /** Program and arguments, such as `npx -y @agentclientprotocol/claude-agent-acp`. */
    val command: List<String>,
    /** Variables added to the environment of the agent (an API key). */
    val environment: Map<String, String> = emptyMap(),
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
    Read,
    Edit,
    Delete,
    Move,
    Search,
    Execute,
    Think,
    Fetch,
    Other,
}

/** Progress of a tool call. */
enum class AgentToolStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

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

/** A choice offered by a permission request: allow once, allow always, reject… */
data class AgentPermissionOption(
    /** Id the agent gave the option. */
    val id: String,
    /** Label of the button. */
    val name: String,
    /** `allow_once`, `allow_always`, `reject_once` or `reject_always`. */
    val kind: String,
)

/**
 * One entry of a transcript. [id] never changes and is unique across restarts of the server; [version] is the
 * version of the transcript when the item last changed, so a client asks only for the items newer than its own.
 */
sealed interface AgentItem {
    val id: String
    val version: Long

    /** A prompt of the user. */
    data class UserText(override val id: String, override val version: Long, val text: String) : AgentItem

    /** Text of the agent, streamed: it grows until another item starts. */
    data class AgentText(override val id: String, override val version: Long, val text: String) : AgentItem

    /** What the agent thinks before it answers, streamed like [AgentText]. */
    data class Thought(override val id: String, override val version: Long, val text: String) : AgentItem

    /** An action of the agent on the project: read a file, edit it, run a command… */
    data class ToolCall(
        override val id: String,
        override val version: Long,
        val title: String,
        val kind: AgentToolKind,
        val status: AgentToolStatus,
        /** Text the tool produced (its output, or why it failed). */
        val output: String = "",
        /** Files it changes. */
        val diffs: List<AgentDiff> = emptyList(),
        /** Files it works on, absolute. */
        val locations: List<String> = emptyList(),
    ) : AgentItem

    /** The plan of the agent for the current prompt, replaced as it goes. */
    data class Plan(override val id: String, override val version: Long, val entries: List<AgentPlanEntry>) :
        AgentItem

    /**
     * The agent asks before it runs a tool call. [chosen] is the option answered, null while it waits; a request
     * left without an answer when the prompt is cancelled or the agent stops is [cancelled].
     */
    data class Permission(
        override val id: String,
        override val version: Long,
        val title: String,
        val options: List<AgentPermissionOption>,
        val chosen: String? = null,
        val cancelled: Boolean = false,
    ) : AgentItem {
        /** Whether it still waits for the user. */
        val pending: Boolean get() = chosen == null && !cancelled
    }

    /** Something the IDE says about the conversation: stopped, the agent failed, a new session started… */
    data class Notice(override val id: String, override val version: Long, val text: String, val error: Boolean) :
        AgentItem
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
    val known: Boolean,
    val state: AgentTurnState,
    /** Items that changed since the version asked for, oldest first. */
    val items: List<AgentItem>,
    /** Name of the agent and its version, as it introduced itself; null before it started. */
    val agentName: String? = null,
)

/** Conversations with agents, run by the server. Every call returns at once except [transcript], a long poll. */
interface Agents {
    /**
     * Sends [text] to the agent of [conversation], started with [agent] in [directory] if it does not run yet. The
     * prompt shows as item [itemId]. A request sent twice is run once.
     */
    suspend fun prompt(conversation: String, agent: AgentCommand, directory: String, itemId: String, text: String)

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
    object None : Agents {
        override suspend fun prompt(
            conversation: String,
            agent: AgentCommand,
            directory: String,
            itemId: String,
            text: String,
        ): Unit = throw java.io.IOException("No server runs the agents")

        override suspend fun cancel(conversation: String) = Unit

        override suspend fun answer(conversation: String, itemId: String, option: String) = Unit

        override suspend fun transcript(conversation: String, epoch: String, since: Long): AgentTranscript =
            kotlinx.coroutines.awaitCancellation()

        override suspend fun close(conversation: String) = Unit
    }
}
