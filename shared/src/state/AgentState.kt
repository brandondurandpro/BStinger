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
import org.example.kotlineditor.model.FileContent

private const val TITLE_LENGTH = 40

/** One entry of a conversation with the agent. */
sealed interface AgentMessage {
    class User(val text: String) : AgentMessage

    class Agent(val text: String) : AgentMessage

    /** An action of the agent on the project (read a file, run a command…), shown as a card. */
    @Stable
    class ToolCall(val tool: String, val target: String) : AgentMessage {
        var running by mutableStateOf(true)
            internal set
        var output by mutableStateOf("")
            internal set
    }
}

/** Where the agent of a conversation works. */
enum class WorkMode {
    /** On the branch checked out in the project, in the project itself: its changes are the project's. */
    Direct,

    /** On a branch of its own, checked out in a Git worktree: the conversations never mix their changes. */
    Worktree,
}

/**
 * A conversation with the agent. Its files, Git panel and terminal are those of [workspace]: the project itself in
 * [WorkMode.Direct], the worktree of [branch] in [WorkMode.Worktree].
 */
@Stable
class Conversation(val id: Int, title: String, val branch: String) {
    var title by mutableStateOf(title)
        internal set
    val messages = mutableStateListOf<AgentMessage>()

    /** Text being typed in the prompt, kept per conversation like an unsent draft. */
    var draft by mutableStateOf("")

    /** True while the agent is answering; the prompt then offers to stop it. */
    var busy by mutableStateOf(false)
        internal set
    internal var job: Job? = null

    /** Direct by default; the agent moves to a worktree of [branch] on request. */
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
    val currentBranch: String? get() = workspace?.gitBranch ?: branch.takeIf { mode == WorkMode.Worktree }

    /** Scroll position of the messages, kept when switching tabs. */
    val listState = LazyListState()

    /** Last line shown under the title in the conversation list. */
    val preview: String
        get() =
            when (val last = messages.lastOrNull()) {
                is AgentMessage.User -> last.text
                is AgentMessage.Agent -> last.text
                is AgentMessage.ToolCall -> "${last.tool} ${last.target}"
                null -> "No messages yet"
            }.lineSequence().first()
}

/**
 * Agent mode (proof of concept): the conversations of [project], listed on the left and opened in tabs, each one in
 * a worktree of its branch. No model is plugged in: [send] answers with a simulated agent working on the real files.
 */
@Stable
class AgentState(private val project: ProjectState) {
    private val git get() = project.services.git
    val conversations = mutableStateListOf<Conversation>().apply { addAll(sampleConversations()) }

    /** Conversations opened in the tabs of agent mode. */
    val tabs = AgentTabs()

    /** Conversation of the active tab: the tool windows and the toolbar show its worktree and its branch. */
    val current: Conversation? get() = tabs.active

    /** Whether the conversation list is shown on the left. */
    var conversationsVisible by mutableStateOf(true)
    private var nextId = conversations.size + 1

    init {
        followEditors()
    }

    /** Opens [conversation] in a tab, or selects its tab, and prepares its worktree. */
    fun open(conversation: Conversation) {
        tabs.open(conversation)
        prepare(conversation)
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

    /** Deletes [conversation] and its worktree, if it has one (Git keeps it when it has uncommitted changes). */
    fun delete(conversation: Conversation) {
        stop(conversation)
        tabs.close(conversation)
        conversations.remove(conversation)
        closeWorktree(conversation)?.let { worktree ->
            project.scope.launch { runCatching { git.removeWorktree(project.root.path, worktree.root.path) } }
        }
    }

    /**
     * Moves the agent of [conversation] to [mode]: into a worktree of its branch (created on first use), or back to
     * the branch of the project. A worktree left stays on disk with its changes, and is reused when coming back.
     */
    fun setMode(conversation: Conversation, mode: WorkMode) {
        if (conversation.mode == mode || conversation.busy || conversation.preparingWorktree) return
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

    /** Sends the draft of [conversation] and starts the simulated agent. */
    fun send(conversation: Conversation) {
        val text = conversation.draft.trim()
        if (text.isEmpty() || conversation.busy) return
        conversation.draft = ""
        if (conversation.messages.isEmpty()) conversation.title = titleOf(text)
        conversation.messages.add(AgentMessage.User(text))
        conversations.remove(conversation)
        conversations.add(0, conversation)
        conversation.busy = true
        conversation.job =
            project.scope.launch {
                try {
                    prepare(conversation)
                    conversation.preparing?.join()
                    SimulatedAgent(conversation).answer(text)
                } finally {
                    conversation.messages.filterIsInstance<AgentMessage.ToolCall>().forEach { it.running = false }
                    conversation.busy = false
                }
            }
    }

    fun stop(conversation: Conversation) {
        if (!conversation.busy) return
        conversation.job?.cancel()
        conversation.messages.add(AgentMessage.Agent("Stopped."))
    }

    /** Closes the worktrees when the project closes; they stay on disk for the next session. */
    fun dispose() {
        conversations.forEach { closeWorktree(it) }
    }

    // Closes the worktree project of the conversation (never the project itself) and returns it.
    private fun closeWorktree(conversation: Conversation): ProjectState? {
        conversation.preparing?.cancel()
        val worktree = conversation.workspace?.takeIf { it !== project }
        worktree?.dispose()
        conversation.workspace = null
        conversation.workspaceError = null
        return worktree
    }

    // The project itself in direct mode; otherwise the worktree of the branch, created by the server on first use.
    private fun prepare(conversation: Conversation) {
        if (conversation.workspace != null || conversation.preparing?.isActive == true) return
        conversation.workspaceError = null
        if (conversation.mode == WorkMode.Direct) {
            conversation.workspace = project
            return
        }
        conversation.preparing =
            project.scope.launch {
                runCatching { project.openWorktree(conversation.branch) }
                    .onSuccess { conversation.workspace = it }
                    .onFailure { conversation.workspaceError = it.message ?: "The worktree could not be created" }
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
                    if (previous == place && tab != null && project.agentMode) conversation.showsChat = false
                    previous = place
                }
        }
    }
}

/** Text of a text file, or `null` for a binary one. */
internal fun FileContent.textOrNull(): String? = (this as? FileContent.Text)?.lines?.joinToString(lineSeparator)

internal fun titleOf(text: String): String {
    val line = text.lineSequence().first()
    return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
}
