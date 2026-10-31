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
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitFileStatus

private val INDEXED_POPUPS = setOf(PopupKind.GotoFile, PopupKind.FindInFiles, PopupKind.GotoSymbol)
private val DOCUMENT_POPUPS = setOf(PopupKind.GotoLine, PopupKind.FileStructure)

/** An open project: its tree, editors, index, terminal and tool windows. See `ProjectActions.kt` for actions. */
@Stable
@Suppress("TooManyFunctions")
class ProjectState(
    val root: FileEntry,
    val services: AppServices,
    parentScope: CoroutineScope,
    preferences: Map<String, String> = emptyMap(),
) {
    /** Cancelled when the project is closed, so no background work outlives it. */
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val name: String get() = root.name
    val tree = FileTreeState(root, services.fileSystem, scope)
    val editor: EditorState =
        EditorState(
            services.fileSystem,
            scope,
            tree::relativePath,
            onSaved = { entry ->
                editor.reloadGitOf(entry)
                refreshGitStatus(reloadEditors = false)
            },
            diffs = { entry -> services.git.diff(root.path, tree.relativePath(entry)) },
            commitDiffs = { entry, commit ->
                services.git.commitDiff(root.path, commit.hash, tree.relativePath(entry), commit.oldPath)
            },
            blames = { entry -> services.git.blame(root.path, tree.relativePath(entry)) },
        )
    val fileIndex = FileIndex(root, services.search, scope)
    val find = FindState()
    val terminal = TerminalState(root.path, services.terminal, scope)
    val toolWindows = ToolWindowLayout().apply { restore(preferences) }

    /** The Branches tool window: branches and the history of the selected one. */
    val history = BranchHistoryState(root.path, services.git, scope)
    val network = NetworkView(services.network)
    var popup: PopupKind? by mutableStateOf(null)
    var fileDialog: FileDialog? by mutableStateOf(null)

    /** "Always Select Opened File" (IntelliJ): the active file is selected and revealed in the project tree. */
    var autoSelectOpenedFile by
        mutableStateOf(preferences[PreferenceKeys.AUTO_SELECT_OPENED_FILE]?.toBooleanStrictOrNull() ?: true)

    /** Diff viewer layout: old and new files side by side (two panes) instead of the unified view. */
    var diffSideBySide by mutableStateOf(false)

    /** Message of the last Git action (commit, branch switch, pull, push), shown in the Git panel. */
    var gitMessage: String? by mutableStateOf(null)

    /** True when [gitMessage] reports a failure. */
    var gitMessageIsError by mutableStateOf(false)

    /** True while a Git action is running, so its buttons do not start another one. */
    var gitBusy by mutableStateOf(false)

    /** Text of the commit message field of the Git panel. */
    var commitMessage by mutableStateOf("")

    /** Local branches of the repository, for the branch menu of the Git panel. */
    var gitBranches: List<String> by mutableStateOf(emptyList())
        private set

    /** Local branches with their remote synchronization, for the branch picker of the toolbar. */
    var gitBranchInfos: List<GitBranchInfo> by mutableStateOf(emptyList())
        private set

    /** Branches the user pinned in the toolbar (one or several); clicking a branch name still checks it out. */
    var selectedBranches: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Branches ticked in the picker for a bulk action (pin, delete), as opposed to [selectedBranches], which stay
     * pinned in the toolbar. Kept while the query changes, and pruned when a branch disappears.
     */
    var pickedBranches: Set<String> by mutableStateOf(emptySet())
        private set

    /** The last pull or push of a pinned branch, shown next to the toolbar chips; null once dismissed. */
    var branchSync: BranchSync? by mutableStateOf(null)

    /** Whether the toolbar's branch picker is open. */
    var branchPickerOpen by mutableStateOf(false)

    /** Current Git branch (or short commit when detached), shown in the toolbar like IntelliJ's VCS widget. */
    var gitBranch: String? by mutableStateOf(null)
        private set

    /** Git status of changed files, keyed by project-relative path (colors the tree, lists the Git panel). */
    var gitStatus: Map<String, GitFileStatus> by mutableStateOf(emptyMap())
        private set

    /** False when the project is not a Git repository (or Git is unavailable). */
    var isGitRepository by mutableStateOf(true)
        private set

    /** Positions left by navigation actions, for Ctrl+Alt+Left (Back). */
    internal val backStack = ArrayDeque<Pair<FileEntry, TextPos>>()

    private var gitJob: Job? = null

    // A refresh asked to reload every editor; kept when a later refresh (a save) cancels it.
    private var editorsReloadPending = false

    init {
        refreshGit()
        followActiveFile()
    }

    /** Shows or hides a tool window (Alt+1, Alt+9, Alt+F12, Ctrl+Alt+Shift+D or its button in the left strip). */
    fun toggleToolWindow(window: ToolWindow) {
        toolWindows.toggle(window)
        if (window == ToolWindow.Terminal && toolWindows.isVisible(window)) terminal.ensureStarted()
        if (window == ToolWindow.Git && toolWindows.isVisible(window)) refreshGitStatus()
        if (window == ToolWindow.Branches && toolWindows.isVisible(window)) openHistory()
    }

    /** Opens the Branches tool window on the history of [ref], or of the current branch. */
    fun showHistory(ref: String? = null) {
        showToolWindow(ToolWindow.Branches)
        if (ref != null && ref != history.selectedRef) history.select(ref)
    }

    // Shown again: the branches and the history may have changed meanwhile. The first time, the panel itself selects
    // the current branch once it is known (`BranchesPanel`).
    private fun openHistory() {
        refreshBranches()
        if (history.selectedRef != null) history.reload()
    }

    /** Shows [window] if it is hidden (a pinned branch's Show Changes opens the Git tool window). */
    fun showToolWindow(window: ToolWindow) {
        if (!toolWindows.isVisible(window)) toggleToolWindow(window)
    }

    fun showPopup(kind: PopupKind) {
        if (kind in INDEXED_POPUPS) fileIndex.ensureLoaded()
        if (kind !in DOCUMENT_POPUPS || editor.activeTab?.document != null) popup = kind
    }

    fun openFile(entry: FileEntry) {
        if (entry.isDirectory) tree.toggle(entry) else editor.open(entry)
    }

    /** Reloads the Git branch and the status of every file. */
    fun refreshGit() {
        refreshBranches()
        refreshGitStatus()
    }

    /**
     * Reloads the branches and their distance from their remote-tracking branch. A failed call (server unreachable)
     * keeps the last list, so the branch picker still shows what it knows while the link comes back.
     */
    fun refreshBranches() {
        scope.launch {
            runCatching { services.git.branch(root.path) }.onSuccess {
                gitBranch = it
                isGitRepository = it != null
            }
            if (isGitRepository) {
                runCatching { services.git.branchDetails(root.path) }
                    .onSuccess(::applyBranches)
                    .onFailure {
                        // An older server knows the names but not their upstream: show the names at least.
                        runCatching { services.git.branches(root.path) }
                            .onSuccess { names -> applyBranches(names.map(::branchInfoOf)) }
                    }
            }
        }
    }

    private fun applyBranches(infos: List<GitBranchInfo>) {
        gitBranchInfos = sortedBranches(infos)
        gitBranches = infos.map { it.name }
        val names = infos.mapTo(HashSet()) { it.name }
        selectedBranches = selectedBranches intersect names
        pickedBranches = pickedBranches intersect names
    }

    /** Pins or unpins [name] in the toolbar. */
    fun toggleBranchSelection(name: String) {
        selectedBranches =
            if (name in selectedBranches) selectedBranches - name else selectedBranches + name
    }

    /** Ticks or unticks [name] for a bulk action (the checkbox of the branch picker). */
    fun toggleBranchPick(name: String) {
        pickedBranches = if (name in pickedBranches) pickedBranches - name else pickedBranches + name
    }

    fun clearBranchPicks() {
        pickedBranches = emptySet()
    }

    /** Ticks every branch of [names] (the picker's Select all, which ticks the branches the query shows). */
    fun pickBranches(names: Collection<String>) {
        pickedBranches = pickedBranches + names
    }

    // Branches just deleted: they leave the toolbar and the picks at once, before the refresh lists the others.
    internal fun forgetBranches(names: Set<String>) {
        selectedBranches = selectedBranches - names
        pickedBranches = pickedBranches - names
        gitBranchInfos = gitBranchInfos.filter { it.name !in names }
        gitBranches = gitBranches.filter { it !in names }
    }

    /** Pins every ticked branch in the toolbar. */
    fun pinPickedBranches() {
        selectedBranches = selectedBranches + pickedBranches
    }

    /** Removes every ticked branch from the toolbar chips. */
    fun unpinPickedBranches() {
        selectedBranches = selectedBranches - pickedBranches
    }

    /** Local branches whose remote is missing or gone, other than the current one: the cleanup shortcut's target. */
    fun branchesWithoutRemote(): List<GitBranchInfo> = gitBranchInfos.filter { !it.current && !it.hasUpstream }

    private fun branchInfoOf(name: String) =
        GitBranchInfo(name = name, current = name == gitBranch, upstream = null, ahead = 0, behind = 0)

    // Current branch first, then by name: the branch picker and the toolbar chips keep a stable, familiar order.
    private fun sortedBranches(infos: List<GitBranchInfo>) =
        infos.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenBy { it.name })

    /**
     * Reloads the status of every file and, when [reloadEditors], the diffs, gutter marks and blame of the open
     * editors (HEAD may have moved). A save passes false: it reloads only the saved file (`EditorState.reloadGitOf`).
     */
    fun refreshGitStatus(reloadEditors: Boolean = true) {
        editorsReloadPending = editorsReloadPending || reloadEditors
        gitJob?.cancel()
        gitJob =
            scope.launch {
                runCatching { services.git.status(root.path) }.onSuccess {
                    gitStatus = it
                    if (editorsReloadPending) {
                        editorsReloadPending = false
                        editor.reloadDiffs()
                        editor.reloadChanges()
                        editor.reloadBlames()
                    }
                }
            }
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
        terminal.stop()
        scope.cancel()
    }
}
