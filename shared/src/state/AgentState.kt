package org.example.kotlineditor.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.FileContent
import java.io.IOException
import java.util.UUID

private const val TITLE_LENGTH = 40

/** Where the agent of a conversation works. */
enum class WorkMode {
    /** On the branch checked out in the project, in the project itself: its changes are the project's. */
    Direct,

    /** On a branch of its own, checked out in a Git worktree: the conversations never mix their changes. */
    Worktree,
}

/**
 * A conversation with the agent. Its files, Git panel and terminal are those of [workspace]: the project itself in
 * [WorkMode.Direct], the worktree of [branch] in [WorkMode.Worktree]. Its [items] are the transcript the server keeps,
 * followed as it changes; [key] names it on the server.
 */
@Stable
class Conversation(val id: Int, title: String, val branch: String) {
    /** Name of the conversation on the server, unique across projects and IDEs. */
    val key: String = UUID.randomUUID().toString()

    var title by mutableStateOf(title)
        internal set

    /** The transcript, oldest first. */
    val items = mutableStateListOf<AgentItem>()
    private val positions = HashMap<String, Int>()

    /** Text being typed in the prompt, kept per conversation like an unsent draft. */
    var draft by mutableStateOf("")

    /** What the agent is doing, as the server last said. */
    var state by mutableStateOf(AgentTurnState.Idle)
        internal set

    /** True from the moment a prompt is sent until the server took it. */
    var sending by mutableStateOf(false)
        internal set

    /** True while the agent is answering; the prompt then offers to stop it. */
    val busy: Boolean get() = sending || state != AgentTurnState.Idle

    /** Name and version of the agent, once it introduced itself. */
    var agentName: String? by mutableStateOf(null)
        internal set

    internal var job: Job? = null
    internal var polling: Job? = null

    // Position in the transcript of the server: its instance, and the version of the latest change seen.
    internal var epoch = ""
    internal var since = 0L

    /**
     * Id of the session of the agent, which keeps the history: the conversation reopens it after a restart of the
     * server, and a conversation of the history opens it. Never stored by the IDE.
     */
    var session: String? by mutableStateOf(null)
        internal set

    // Directory of a session of the history, until it opened; the epoch whose replay replaced the items.
    internal var sessionDirectory: String? = null
    internal var replayedEpoch = ""

    /** Direct by default; the agent moves to a worktree of [branch] on request, before the first prompt. */
    var mode by mutableStateOf(WorkMode.Direct)
        internal set

    /** The project the agent works on: the project itself, or its worktree once Git has created it. */
    var workspace: ProjectState? by mutableStateOf(null)
        internal set

    /** Why the worktree could not be created (not a Git repository, branch checked out elsewhere…). */
    var workspaceError: String? by mutableStateOf(null)
        internal set
    internal var preparing: Job? = null

    /** True while the agent moves to a worktree: the tool windows still show where it worked before. */
    var preparingWorktree by mutableStateOf(false)
        internal set

    /** True when the conversation itself is shown; false for the files and diffs open in its worktree. */
    var showsChat by mutableStateOf(true)

    /** Branch checked out where the agent works: it follows a checkout made from the Git panel or the terminal. */
    val currentBranch: String? get() = workspace?.git?.currentBranch ?: branch.takeIf { mode == WorkMode.Worktree }

    /** Whether the agent already works somewhere: its place can no longer change. */
    val started: Boolean get() = items.isNotEmpty() || session != null

    /** Scroll position of the messages, kept when switching tabs. */
    val listState = LazyListState()

    /** Last line shown under the title in the conversation list. */
    val preview: String
        get() =
            when (val last = items.lastOrNull()) {
                is AgentItem.UserText -> last.text
                is AgentItem.AgentText -> last.text
                is AgentItem.Thought -> last.text
                is AgentItem.ToolCall -> last.title
                is AgentItem.Plan -> "Plan"
                is AgentItem.Permission -> "Asks: ${last.title}"
                is AgentItem.Notice -> last.text
                null -> if (session != null) "From the history of the agent" else "No messages yet"
            }.lineSequence().firstOrNull().orEmpty()

    /**
     * Adds [item], or replaces the item with its id: a newer version of it, or the prompt the server echoed, which
     * moves after what the server showed before it (a replayed history).
     */
    internal fun upsert(item: AgentItem) {
        val position = positions[item.id]
        val echoed = position != null && items[position].version == 0L && item.version > 0L
        if (echoed && position != items.lastIndex) {
            items.removeAt(position)
            reindex()
        }
        val now = positions[item.id]
        if (now == null) {
            positions[item.id] = items.size
            items.add(item)
        } else {
            items[now] = item
        }
    }

    /** Replaces what the conversation shows by the history the agent replayed; the prompts not sent yet stay. */
    internal fun replaceByHistory() {
        val unsent = items.filter { it is AgentItem.UserText && it.version == 0L }
        items.clear()
        positions.clear()
        unsent.forEach(::upsert)
    }

    private fun reindex() {
        positions.clear()
        items.forEachIndexed { index, item -> positions[item.id] = index }
    }

    /** A notice of the IDE itself, not of the server. */
    internal fun note(text: String, error: Boolean = true) {
        upsert(AgentItem.Notice("local-${UUID.randomUUID()}", 0, text, error))
    }
}

/**
 * Agent mode: the conversations of [project], listed on the left and opened in tabs, each one on the current branch
 * or in a worktree of its own branch. The agent runs on the server ([org.example.kotlineditor.model.Agents]), started
 * with the command [agentCommand] returns; each conversation follows its transcript with a long poll.
 */
@Stable
class AgentState(
    private val project: ProjectState,
    private val agentCommand: () -> AgentCommand = { agentCommandOf("") },
) {
    private val git get() = project.services.git
    private val agents get() = project.services.agents
    val conversations = mutableStateListOf<Conversation>()

    /** Conversations opened in the tabs of agent mode. */
    val tabs = AgentTabs()

    /** Conversation of the active tab: the tool windows and the toolbar show its worktree and its branch. */
    val current: Conversation? get() = tabs.active

    /** The agent, as shown under the prompt before it introduced itself: its program. */
    val agentLabel: String
        get() {
            val agent = agentCommand()
            return "Agent: ${agent.registry ?: agent.command.joinToString(" ")}"
        }

    /** Whether the conversation list is shown on the left. */
    var conversationsVisible by mutableStateOf(true)
    internal var nextId = 1

    init {
        followEditors()
        followHistory(project, agentCommand)
    }

    /** Opens [conversation] in a tab, or selects its tab, and prepares its worktree. */
    fun open(conversation: Conversation) {
        tabs.open(conversation)
        conversation.prepareIn(project)
        project.restore(conversation, agentCommand())
    }

    /** Ctrl+F4: closes the file or diff shown, or the conversation when it is the one shown. */
    fun closeActive() {
        val conversation = tabs.active ?: return
        val editor = conversation.workspace?.editor
        val tab = editor?.activeTab
        if (conversation.showsChat || tab == null) {
            tabs.close(conversation)
        } else {
            editor.close(tab)
            if (editor.allTabs.isEmpty()) conversation.showsChat = true
        }
    }

    /** Starts a conversation on a branch of its own. */
    fun newConversation(): Conversation {
        val id = nextId++
        val conversation = Conversation(id, "New conversation", "agent/conversation-$id")
        conversations.add(0, conversation)
        open(conversation)
        return conversation
    }

    /** Deletes [conversation], its session and its worktree, if it has one (Git keeps it when it has changes). */
    fun delete(conversation: Conversation) {
        conversation.job?.cancel()
        conversation.polling?.cancel()
        tabs.close(conversation)
        conversations.remove(conversation)
        val started = conversation.started
        val session = conversation.session
        val agent = agentCommand()
        project.scope.launch {
            if (started) runCatching { agents.close(conversation.key) }
            // The agent keeps the history: deleted there, or the conversation comes back with it.
            if (session != null) runCatching { agents.delete(agent, session) }
        }
        conversation.closeWorktree(project)?.let { worktree ->
            project.scope.launch { runCatching { git.removeWorktree(project.root.path, worktree.root.path) } }
        }
    }

    /**
     * Moves the agent of [conversation] to [mode]: into a worktree of its branch (created on first use), or back to
     * the branch of the project. Only before the first prompt: the session of the agent works in one directory.
     */
    fun setMode(conversation: Conversation, mode: WorkMode) {
        val working = conversation.busy || conversation.preparingWorktree
        val locked = working || conversation.started
        if (conversation.mode == mode || locked) return
        val previous = conversation.mode
        conversation.mode = mode
        conversation.showsChat = true
        conversation.workspaceError = null
        if (mode == WorkMode.Direct) {
            conversation.show(project, project)
            return
        }
        // The tool windows keep showing the project until the worktree is loaded, then switch at once.
        conversation.preparingWorktree = true
        conversation.preparing =
            project.scope.launch {
                runCatching { project.openWorktree(conversation.branch) }
                    .onSuccess { conversation.show(it, project) }
                    .onFailure {
                        conversation.mode = previous
                        conversation.workspaceError = it.message ?: "The worktree could not be created"
                    }
                conversation.preparingWorktree = false
            }
    }

    /**
     * Sends the draft of [conversation]: the modified files are saved first, so the agent reads what the user sees,
     * then the server runs the agent and the transcript shows its answer as it streams.
     */
    fun send(conversation: Conversation) {
        val text = conversation.draft.trim()
        if (text.isEmpty() || conversation.busy) return
        conversation.draft = ""
        if (!conversation.started) conversation.title = titleOf(text)
        val itemId = "prompt-${UUID.randomUUID()}"
        // Shown at once; the server's copy of the prompt replaces it, under the same id.
        conversation.upsert(AgentItem.UserText(itemId, 0, text))
        conversations.remove(conversation)
        conversations.add(0, conversation)
        conversation.sending = true
        conversation.job = project.scope.launch { deliver(conversation, itemId, text) }
    }

    private suspend fun deliver(conversation: Conversation, itemId: String, text: String) {
        try {
            conversation.prepareIn(project)
            conversation.preparing?.join()
            val workspace = conversation.workspace
            if (workspace == null) {
                val reason = conversation.workspaceError ?: "its worktree is not ready"
                conversation.note("The agent cannot work on ${conversation.branch}: $reason.")
                return
            }
            workspace.editor.flush()
            project.followTranscript(conversation)
            agents.prompt(conversation.key, agentCommand(), workspace.root.path, itemId, text, conversation.session)
        } catch (failure: IOException) {
            conversation.note("The prompt could not be sent: ${failure.message}")
        } finally {
            conversation.sending = false
        }
    }

    /** Stops the agent of [conversation]; its permission requests are cancelled. */
    fun stop(conversation: Conversation) {
        if (!conversation.busy) return
        project.scope.launch {
            try {
                agents.cancel(conversation.key)
            } catch (failure: IOException) {
                conversation.note("The agent could not be stopped: ${failure.message}")
            }
        }
    }

    /** Answers the permission request [request] of [conversation] with [option]. */
    fun answer(conversation: Conversation, request: AgentItem.Permission, option: String) {
        if (!request.pending) return
        project.scope.launch {
            try {
                agents.answer(conversation.key, request.id, option)
            } catch (failure: IOException) {
                conversation.note("The answer could not be sent: ${failure.message}")
            }
        }
    }

    /** Closes the worktrees when the project closes; they stay on disk for the next session. */
    fun dispose() {
        conversations.forEach {
            it.polling?.cancel()
            it.closeWorktree(project)
        }
    }

    // A file or a diff opened from the tool windows is shown in place of the active conversation; selecting another
    // conversation, or its worktree opening, changes nothing.
    private fun followEditors() {
        project.scope.launch {
            var previous: Pair<Conversation, ProjectState?>? = null
            snapshotFlow { current?.let { Triple(it, it.workspace, it.workspace?.editor?.activeTab) } }
                .filterNotNull()
                .collect { (conversation, workspace, tab) ->
                    val place = conversation to workspace
                    val revisited = previous == place
                    val inAgentTab = tab != null && project.agentMode
                    if (revisited && inAgentTab) conversation.showsChat = false
                    previous = place
                }
        }
    }
}

/** Text of a text file, or `null` for a binary one. */
internal fun FileContent.textOrNull(): String? = (this as? FileContent.Text)?.lines?.joinToString(lineSeparator)

internal fun titleOf(text: String): String {
    val line = text.lineSequence().firstOrNull().orEmpty()
    return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
}
