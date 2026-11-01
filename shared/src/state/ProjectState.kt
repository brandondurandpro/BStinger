package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.syntax.treesitter.GrammarStore

private val INDEXED_POPUPS = setOf(PopupKind.GotoFile, PopupKind.FindInFiles, PopupKind.GotoSymbol)
private val DOCUMENT_POPUPS = setOf(PopupKind.GotoLine, PopupKind.FileStructure)

/** An open project: its tree, editors, index, terminal and tool windows. See `ProjectActions.kt` for actions. */
@Stable
@Suppress("TooManyFunctions")
class ProjectState(
    override val root: FileEntry,
    val services: AppServices,
    parentScope: CoroutineScope,
    preferences: Map<String, String> = emptyMap(),
    /** Open tool windows and their docks; a worktree of agent mode shares the one of its project. */
    override val toolWindows: ToolWindowLayout = ToolWindowLayout().apply { restore(preferences) },
    /** Tree-sitter grammars, shared with the worktrees agent mode opens from this project. */
    internal val grammars: GrammarStore? = null,
    /** Language servers of the IDE, shared by every project; none for the worktrees of agent mode. */
    override val languageServers: LanguageServersState? = null,
) : GitHost,
    TreeHost,
    LanguageServersHost {
    /** Cancelled when the project is closed, so no background work outlives it. */
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val name: String get() = root.name
    override val tree = FileTreeState(root, services.fileSystem, scope)
    override val editor: EditorState =
        EditorState(
            services.fileSystem,
            scope,
            tree::relativePath,
            onSaved = { entry ->
                editor.reloadGitOf(entry)
                git.refreshStatus(reloadEditors = false)
            },
            diffs = { entry -> services.git.diff(root.path, tree.relativePath(entry)) },
            commitDiffs = { entry, commit ->
                services.git.commitDiff(root.path, commit.hash, tree.relativePath(entry), commit.oldPath)
            },
            blames = { entry -> services.git.blame(root.path, tree.relativePath(entry)) },
            grammars = grammars,
        )
    override val fileIndex = FileIndex(root, services.search, scope)
    val find = FindState()

    /** Code completion in the editors, from the language servers. */
    val completion =
        CompletionState(scope) { path, document ->
            languageServers?.complete(path, document) ?: CompletionResult.NotReady
        }
    val terminal = TerminalState(root.path, services.terminal, scope)

    /** Git: branch, status, commit, branches of the toolbar, and the Branches tool window's history. */
    override val git = GitState(root, services.git, scope, editor, synchronize = ::synchronize)
    val network = NetworkView(services.network)

    // Created on first use: the worktrees of the conversations are projects too, without agent mode of their own.
    private val agentState = lazy { AgentState(this) }

    /** Conversations of agent mode (proof of concept, in memory), each in a worktree of its branch. */
    val agent by agentState

    override val gitStatus: Map<String, GitFileStatus> get() = git.status

    /** True when the window shows agent mode instead of the editors (toolbar switch, Ctrl+Alt+A). */
    var agentMode by mutableStateOf(false)
    var popup: PopupKind? by mutableStateOf(null)
    override var fileDialog: FileDialog? by mutableStateOf(null)

    /** "Always Select Opened File" (IntelliJ): the active file is selected and revealed in the project tree. */
    override var autoSelectOpenedFile by
        mutableStateOf(preferences[PreferenceKeys.AUTO_SELECT_OPENED_FILE]?.toBooleanStrictOrNull() ?: true)

    /** Diff viewer layout: old and new files side by side (two panes) instead of the unified view. */
    var diffSideBySide by mutableStateOf(false)

    /** Positions left by navigation actions, for Ctrl+Alt+Left (Back). */
    internal val backStack = ArrayDeque<Pair<FileEntry, TextPos>>()

    init {
        git.refresh()
        followActiveFile()
    }

    /** Shows or hides a tool window (Alt+1, Alt+9, Alt+F12, Ctrl+Alt+Shift+D or its button in the left strip). */
    fun toggleToolWindow(window: ToolWindow) {
        toolWindows.toggle(window)
        if (window == ToolWindow.Terminal && toolWindows.isVisible(window)) terminal.ensureStarted()
        if (window == ToolWindow.Git && toolWindows.isVisible(window)) git.refreshStatus()
        if (window == ToolWindow.Branches && toolWindows.isVisible(window)) openHistory()
    }

    /** Opens the Branches tool window on the history of [ref], or of the current branch. */
    override fun showHistory(ref: String?) {
        showToolWindow(ToolWindow.Branches)
        if (ref != null && ref != git.history.selectedRef) git.history.select(ref)
    }

    // Shown again: the branches and the history may have changed meanwhile. The first time, the panel itself selects
    // the current branch once it is known (`BranchesPanel`).
    private fun openHistory() {
        git.refreshBranches()
        if (git.history.selectedRef != null) git.history.reload()
    }

    /** Shows [window] if it is hidden (a pinned branch's Show Changes opens the Git tool window). */
    override fun showToolWindow(window: ToolWindow) {
        if (!toolWindows.isVisible(window)) toggleToolWindow(window)
    }

    fun showPopup(kind: PopupKind) {
        if (kind in INDEXED_POPUPS) fileIndex.ensureLoaded()
        if (kind !in DOCUMENT_POPUPS || editor.activeTab?.document != null) popup = kind
    }

    override fun openFile(entry: FileEntry) {
        if (entry.isDirectory) tree.toggle(entry) else editor.open(entry)
    }

    /** Opens the diff tab of the file at [relativePath] (a click on a Git panel change). */
    override fun openDiff(relativePath: String) {
        editor.openDiff(entryAt(relativePath))
    }

    /** Opens the diff of [file] in [commit] (a click on a changed file in the Branches window). */
    override fun openCommitDiff(commit: GitCommit, file: GitCommitFile) {
        val target = CommitDiffTarget(commit.hash, commit.shortHash, file.oldPath)
        editor.openCommitDiff(entryAt(file.path), target)
    }

    /** Opens [entry] at [start]..[end], remembering where we came from. */
    override fun navigate(entry: FileEntry, start: TextPos, end: TextPos) {
        val tab = editor.activeTab
        val document = tab?.document
        if (tab != null && document != null) backStack.addLast(tab.entry to document.caret)
        if (backStack.size > MAX_BACK_STEPS) backStack.removeFirst()
        editor.openAt(entry, start, end)
    }

    // Selects the active file in the tree whenever it changes (and when the option is turned back on).
    private fun followActiveFile() {
        scope.launch {
            snapshotFlow {
                editor.activeTab
                    ?.entry
                    ?.path
                    ?.takeIf { autoSelectOpenedFile }
            }.filterNotNull()
                .collect { tree.reveal(it) }
        }
    }

    fun dispose() {
        if (agentState.isInitialized()) agent.dispose()
        terminal.stop()
        scope.cancel()
    }
}
