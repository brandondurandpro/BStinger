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
import org.example.kotlineditor.model.GitFileStatus

private val INDEXED_POPUPS = setOf(PopupKind.GotoFile, PopupKind.FindInFiles, PopupKind.GotoSymbol)
private val DOCUMENT_POPUPS = setOf(PopupKind.GotoLine, PopupKind.FileStructure)

/** An open project: its tree, editors, index, terminal and tool windows. See `ProjectActions.kt` for actions. */
@Stable
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
    val editor =
        EditorState(
            services.fileSystem,
            scope,
            tree::relativePath,
            onSaved = { refreshGitStatus() },
            diffs = { entry -> services.git.diff(root.path, tree.relativePath(entry)) },
            blames = { entry -> services.git.blame(root.path, tree.relativePath(entry)) },
        )
    val fileIndex = FileIndex(root, services.search, scope)
    val find = FindState()
    val terminal = TerminalState(root.path, services.terminal, scope)
    val toolWindows = ToolWindowLayout().apply { restore(preferences) }
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

    init {
        refreshGit()
        followActiveFile()
    }

    /** Shows or hides a tool window (Alt+1, Alt+9, Alt+F12 or its button in the left strip). */
    fun toggleToolWindow(window: ToolWindow) {
        toolWindows.toggle(window)
        if (window == ToolWindow.Terminal && toolWindows.isVisible(window)) terminal.ensureStarted()
        if (window == ToolWindow.Git && toolWindows.isVisible(window)) refreshGitStatus()
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
        scope.launch {
            gitBranch = runCatching { services.git.branch(root.path) }.getOrNull()
            isGitRepository = gitBranch != null
            gitBranches =
                if (isGitRepository) {
                    runCatching { services.git.branches(root.path) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
        }
        refreshGitStatus()
    }

    fun refreshGitStatus() {
        gitJob?.cancel()
        gitJob =
            scope.launch {
                runCatching { services.git.status(root.path) }.onSuccess {
                    gitStatus = it
                    editor.reloadDiffs()
                    editor.reloadChanges()
                    editor.reloadBlames()
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
