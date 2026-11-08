package org.example.kotlineditor.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSlashCommand
import org.example.kotlineditor.model.AgentUsage
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
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

    /** The uncommitted changes where the agent works, to review. */
    val changes = ChangesState()

    /** True while the conversation is archived: out of the list, found again by the search. */
    var archived by mutableStateOf(false)
        internal set

    /** True while the conversation is pinned to the top of the list. */
    var pinned by mutableStateOf(false)
        internal set

    /** True once the user gave the conversation its title: the one the agent proposes no longer replaces it. */
    var renamed by mutableStateOf(false)
        internal set

    /** The transcript, oldest first. */
    val items = mutableStateListOf<AgentItem>()
    private val positions = HashMap<String, Int>()

    /** Which of the prompts already sent the Up key brought back to the draft (0 = the latest); -1 when none. */
    internal var recalled = -1

    /** Text being typed in the prompt and its caret, kept per conversation like an unsent draft. */
    var draftValue by mutableStateOf(TextFieldValue(""))

    /** Text being typed in the prompt; setting it puts the caret at its end. */
    var draft: String
        get() = draftValue.text
        set(value) {
            draftValue = TextFieldValue(value, TextRange(value.length))
        }

    /** What the user joins to the next prompt: files, lines, images. */
    val context = PromptContext()

    /** The commands the agent offers in its session (`/compact`…); empty before it said. */
    var commands: List<AgentSlashCommand> by mutableStateOf(emptyList())

    /** The context the session uses and what it cost, as the agent last said; null before it did. */
    var usage: AgentUsage? by mutableStateOf(null)

    /** The remaining quota of the plan of the agent, as its latest answer to a command said (`/status`); empty before. */
    var quota: List<QuotaLimit> by mutableStateOf(emptyList())
        internal set

    /** What the agent is doing, as the server last said. */
    var state by mutableStateOf(AgentTurnState.Idle)
        internal set

    /** True from the moment a prompt is sent until the server took it. */
    var sending by mutableStateOf(false)
        internal set

    /** True while the agent is answering; the prompt then offers to stop it. */
    val busy: Boolean get() = sending || state != AgentTurnState.Idle

    /** True once the agent finished its turn, until the next prompt is sent: the work is done and waits for a look. */
    var finished by mutableStateOf(false)
        internal set

    /** True once the user looked at the conversation after the agent finished its turn; false again at the next end. */
    var seen by mutableStateOf(false)
        internal set

    /** When the last message was sent or received (milliseconds since the epoch); null when none is known. */
    var lastMessageAt: Long? by mutableStateOf(null)
        internal set

    /** When the agent started the turn it is working on (milliseconds since the epoch), null while it is idle. */
    var workingSince: Long? by mutableStateOf(null)
        private set

    /** Starts the clock of the turn when the agent became busy, stops it when it is idle. */
    internal fun syncWorkingClock() {
        workingSince = if (busy) workingSince ?: System.currentTimeMillis() else null
    }

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
    var workspace: AgentWorkspace? by mutableStateOf(null)
        internal set

    /**
     * The coding agent of the conversation when the user chose one in it (one of the server's registry): null when it
     * runs the agent of the settings.
     */
    var agent: AgentCommand? by mutableStateOf(null)
        internal set

    /** Why the worktree could not be created (not a Git repository, branch checked out elsewhere…). */
    var workspaceError: String? by mutableStateOf(null)
        internal set
    internal var preparing: Job? = null

    /**
     * The settings of its session (the model…), as the agent says, or as the user just chose; before the session
     * starts, those a new session offers.
     */
    var options: List<AgentOption> by mutableStateOf(emptyList())
        internal set

    /** The model of its session, when the agent lets the user choose it. */
    val model: AgentOption? get() = options.firstOrNull { it.category == AgentOption.MODEL }

    /** The mode of permissions of its session (ask before editing, accept edits, plan…), when the agent has one. */
    val permissions: AgentOption? get() = options.firstOrNull { it.category == AgentOption.MODE }

    /** How hard the agent thinks (its effort), when its model lets the user choose. */
    val effort: AgentOption? get() = options.firstOrNull { it.category == AgentOption.THOUGHT_LEVEL }
    internal var offering: Job? = null

    // The choices of the user, sent again before the first prompt; how many are on their way; their order.
    internal val choices = LinkedHashMap<String, String>()
    internal var settingOptions = 0

    // The settings as the server last said, shown once no choice is on its way any more.
    internal var serverOptions: List<AgentOption> = emptyList()
    internal val optionsLock = Mutex()

    /** The prompts queued while the agent answered: each goes in turn once it is done. */
    internal val queued = ArrayDeque<OutgoingPrompt>()

    /** Where the items of the silent question about the quota start, null when none is in flight. */
    internal var probeFrom: Int? = null

    /** True when a turn ended since the quota was read: it is read again once the agent is free. */
    internal var quotaStale by mutableStateOf(false)

    /** Ids of the items of the silent questions about the quota: never shown. */
    internal val hidden = HashSet<String>()

    /** True once the quota was asked for: an agent that answers nothing readable is not asked again. */
    internal var quotaProbed = false

    // Called by the transcript when the turn of the agent is over: a queued prompt goes.
    internal var onTurnIdle: (() -> Unit)? = null

    /** True while the agent moves to a worktree: the tool windows still show where it worked before. */
    var preparingWorktree by mutableStateOf(false)
        internal set

    /** True when the conversation itself is shown; false for the files and diffs open in its worktree. */
    var showsChat by mutableStateOf(true)

    /** Branch checked out where the agent works: it follows a checkout made from the Git panel or the terminal. */
    val currentBranch: String? get() = workspace?.checkedOutBranch ?: branch.takeIf { mode == WorkMode.Worktree }

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
    /** The project of agent mode. */
    val project: AgentHost,
    private val agentCommand: () -> AgentCommand = { agentCommandOf("") },
    /** The preferences saved by the last run: the width of the list, and the tabs and titles of this project. */
    private val saved: Map<String, String> = emptyMap(),
) {
    private val git get() = project.services.git
    private val agents get() = project.services.agents
    val conversations = mutableStateListOf<Conversation>()

    /** Conversations opened in the tabs of agent mode. */
    val tabs = AgentTabs()

    /** Conversation of the active tab: the tool windows and the toolbar show its worktree and its branch. */
    val current: Conversation? get() = tabs.active

    /** The agent of [conversation], as shown under the prompt before it introduced itself. */
    fun agentOf(conversation: Conversation): AgentCommand = conversation.agent ?: agentCommand()

    /** The agent of [conversation], named: its registry id, or its command line. */
    fun agentLabelOf(conversation: Conversation): String = labelOf(agentOf(conversation))

    /** The agent of the settings, named in the same way (menu of the agents of a conversation). */
    val settingsAgentLabel: String
        get() = labelOf(agentCommand())

    /** The id of the agent of the settings in the registry, when it comes from it. */
    val settingsAgentId: String?
        get() = agentCommand().registry

    private fun labelOf(agent: AgentCommand): String = agent.registry ?: agent.command.joinToString(" ")

    /** The settings of the sessions (the model…), chosen in the conversations. */
    val options = ConversationOptions(project, ::agentOf)

    /** Whether the conversation list is shown on the left. */
    var conversationsVisible by mutableStateOf(true)

    /** Width of the conversation list in dp, set by dragging its right edge. */
    var conversationsWidth by mutableFloatStateOf(DEFAULT_CONVERSATIONS_WIDTH)
        private set

    /** Widens the conversation list by [delta] dp (narrows it when negative), within its limits. */
    fun resizeConversations(delta: Float) {
        conversationsWidth = (conversationsWidth + delta).coerceIn(MIN_CONVERSATIONS_WIDTH, MAX_CONVERSATIONS_WIDTH)
    }

    /** The agent of the registry the user chose last, which a new conversation starts with; null: the settings'. */
    var lastAgent: String? by mutableStateOf(saved[PreferenceKeys.AGENT_LAST_CHOSEN]?.takeIf { it.isNotBlank() })
        private set

    /** The prompts the user saved to send again, listed in the `/` menu of the conversations. */
    val templates =
        mutableStateListOf<PromptTemplate>().apply {
            addAll(decodeTemplates(saved[PreferenceKeys.AGENT_TEMPLATES]))
            addAll(decodeTemplates(saved[PreferenceKeys.AGENT_TEMPLATES_PREFIX + project.root.path], global = false))
        }

    /**
     * Saves the template [name] with [text], for every project or only this one ([global]); [previous] is the name it had when edited, so a rename replaces it.
     * Another template of the same name is replaced. Returns false when the name or the text is blank.
     */
    fun saveTemplate(previous: String?, name: String, text: String, global: Boolean = true): Boolean {
        val body = text.trim()
        val label = name.trim()
        if (body.isEmpty() || label.isEmpty()) return false
        val index = templates.indexOfFirst { it.name == previous }
        templates.removeAll { it.name == label || it.name == previous }
        val template = PromptTemplate(label, body, global)
        if (index in 0..templates.size) templates.add(index, template) else templates.add(template)
        return true
    }

    /** Forgets the template [name]. */
    fun removeTemplate(name: String) {
        templates.removeAll { it.name == name }
    }

    /** The agents of the registry the user worked with: the history lists the sessions of each, not only the settings'. */
    internal val usedAgents =
        saved[PreferenceKeys.AGENT_USED].orEmpty().lines().filter { it.isNotBlank() }.toMutableList()

    internal var nextId = 1

    // Whether the tabs and titles of the last run were put back: until then, nothing of them is saved.
    internal var restored = false
    internal val savedPreferences get() = saved

    init {
        savedWidth(saved)?.let { conversationsWidth = it }
        followEditors()
        followSeen()
        followChanges()
        followQuota()
        followHistory(project, agentCommand)
    }

    /** Opens [conversation] in a tab, or selects its tab, and prepares its worktree. */
    fun open(conversation: Conversation) {
        tabs.open(conversation)
        conversation.prepareIn(project)
        project.restore(conversation, agentOf(conversation))
        options.offer(conversation)
    }

    /** Opens [conversation] and shows its conversation, even when a file or a diff of its tab strip was shown. */
    fun openChat(conversation: Conversation) {
        open(conversation)
        conversation.showsChat = true
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
        // The state delivers the prompts queued while the agent answered: only it has the command, the scope
        // and the transcript machinery held together (the transcript only says the turn is over).
        conversation.onTurnIdle = { deliverQueued(conversation) }
        conversation.agent = lastAgent?.let { AgentCommand(emptyList(), registry = it) }
        conversations.add(0, conversation)
        open(conversation)
        return conversation
    }

    /** Gives [conversation] the title [title] (blank is ignored); the agent's own titles no longer replace it. */
    fun rename(conversation: Conversation, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        conversation.title = trimmed
        conversation.renamed = true
    }

    /** Pins [conversation] to the top of the list, or unpins it. */
    fun togglePin(conversation: Conversation) {
        conversation.pinned = !conversation.pinned
    }

    /** Archives [conversation], or brings it back; the search of the list still finds an archived one. */
    fun toggleArchive(conversation: Conversation) {
        conversation.archived = !conversation.archived
    }

    /** Deletes [conversation], its session and its worktree, if it has one (Git keeps it when it has changes). */
    fun delete(conversation: Conversation) {
        conversation.job?.cancel()
        conversation.polling?.cancel()
        tabs.close(conversation)
        conversations.remove(conversation)
        val started = conversation.started
        val session = conversation.session
        val agent = agentOf(conversation)
        project.scope.launch {
            if (started) runCatching { agents.close(conversation.key) }
            // The agent keeps the history: deleted there, or the conversation comes back with it.
            if (session != null) runCatching { agents.delete(agent, session) }
        }
        conversation.closeWorktree(project)?.let { worktree ->
            // A reviewer works in the worktree of the conversation it reviews: it goes with the last of them.
            val shared = conversations.any { it.workspace?.root?.path == worktree.root.path }
            if (!shared) {
                project.scope.launch { runCatching { git.removeWorktree(project.root.path, worktree.root.path) } }
            }
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
     * Chooses the agent of [conversation] from the server's registry, or again the agent of the settings when [entry]
     * is null. It applies to its next prompt: a conversation already started keeps its transcript but loses the
     * settings of its previous agent — the prompt runs in a new session of the new agent, which does not remember
     * what the previous one was told (as the transcript says in a notice).
     */
    fun chooseAgent(conversation: Conversation, entry: String?, remember: Boolean = true) {
        if (conversation.busy || conversation.settingOptions > 0) return
        if (remember) lastAgent = entry
        if (entry != null && entry !in usedAgents) usedAgents.add(entry)
        val chosen = entry?.let { AgentCommand(emptyList(), registry = it) }
        if (conversation.agent == chosen) return
        conversation.offering?.cancel()
        conversation.agent = chosen
        conversation.session = null
        conversation.agentName = null
        conversation.commands = emptyList()
        conversation.options = emptyList()
        conversation.choices.clear()
        conversation.serverOptions = emptyList()
        options.offer(conversation)
    }

    /**
     * Sends the draft of [conversation]: the modified files are saved first, so the agent reads what the user sees,
     * then the server runs the agent and the transcript shows its answer as it streams.
     */
    fun send(conversation: Conversation) {
        val text = conversation.draft.trim()
        if (text.isEmpty()) return
        conversation.draft = ""
        if (!conversation.started) conversation.title = titleOf(text)
        val itemId = "prompt-${UUID.randomUUID()}"
        val attachments = conversation.context.take()
        // Shown at once; the server's copy of the prompt replaces it, under the same id.
        conversation.upsert(AgentItem.UserText(itemId, 0, text, attachments.map { it.label }))
        conversations.remove(conversation)
        conversations.add(0, conversation)
        val prompt = OutgoingPrompt(itemId, text, attachments)
        if (conversation.busy) {
            // The agent answers another prompt: this one waits, and goes once it is done (nothing lost, nothing
            // sent) — a keystroke against an answer in flight must not wipe the text the user typed.
            conversation.queued.addLast(prompt)
            return
        }
        launchDelivery(conversation, prompt)
    }

    /**
     * Asks the agent for its quota the way the user would (`/usage`, `/status`), without showing the question or the
     * answer: the bars stay current. Once per finished turn, and once when a started conversation is shown whose
     * quota is not known yet; only when the agent lists such a command.
     */
    internal fun refreshQuota(conversation: Conversation) {
        val unknown = conversation.quota.isEmpty() && conversation.started && readsQuotaAlone(agentOf(conversation))
        val outdated = conversation.quotaStale && conversation.quota.isNotEmpty()
        if (!(outdated || unknown) || conversation.busy || conversation.probeFrom != null) return
        if (conversation.quotaProbed && conversation.quota.isEmpty()) return
        val offered = conversation.commands.map { it.name }
        val command = QUOTA_COMMANDS.firstOrNull { it in offered } ?: return
        conversation.quotaStale = false
        conversation.quotaProbed = true
        conversation.probeFrom = conversation.items.size
        val itemId = "probe-${UUID.randomUUID()}"
        launchDelivery(conversation, OutgoingPrompt(itemId, "/$command", emptyList()))
    }

    /** One prompt in flight; a queued one goes once the turn of the agent is over (see [onTurnIdle]). */
    private fun launchDelivery(conversation: Conversation, prompt: OutgoingPrompt) {
        conversation.sending = true
        conversation.finished = false
        conversation.lastMessageAt = System.currentTimeMillis()
        conversation.syncWorkingClock()
        conversation.job = project.scope.launch { deliver(conversation, prompt) }
    }

    /** The next prompt queued while the agent answered goes once its turn is done. */
    internal fun deliverQueued(conversation: Conversation) {
        if (conversation.queued.isEmpty()) {
            refreshQuota(conversation)
            return
        }
        launchDelivery(conversation, conversation.queued.removeFirst())
    }

    private suspend fun deliver(conversation: Conversation, prompt: OutgoingPrompt) {
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
            options.beforePrompt(conversation)
            project.followTranscript(conversation)
            val directory = workspace.root.path
            val (itemId, text, attachments) = prompt
            agents.prompt(conversation.key, agentOf(conversation), directory, itemId, text, conversation.session, attachments)
        } catch (refused: IOException) {
            if (refused.message == AGENT_BUSY_MESSAGE) {
                // A server that arrived first (or filled its queue) says the agent answers: the prompt goes again
                // once its turn is over, instead of ending the conversation with a notice.
                conversation.queued.addLast(prompt)
            } else {
                conversation.note("The prompt could not be sent: ${refused.message}")
            }
        } finally {
            conversation.sending = false
            conversation.syncWorkingClock()
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

    // The agent finished and the user is looking at the conversation: it is seen.
    private fun followSeen() {
        project.scope.launch {
            snapshotFlow {
                current?.takeIf { project.agentMode && it.showsChat && it.finished && !it.seen }
            }.filterNotNull().collect { it.seen = true }
        }
    }

    // A file or a diff opened from the tool windows is shown in place of the active conversation; selecting another
    // conversation, or its worktree opening, changes nothing.
    private fun followEditors() {
        project.scope.launch {
            var previous: Pair<Conversation, AgentWorkspace?>? = null
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

private const val DEFAULT_CONVERSATIONS_WIDTH = 260f
private const val MIN_CONVERSATIONS_WIDTH = 160f
private const val MAX_CONVERSATIONS_WIDTH = 640f
