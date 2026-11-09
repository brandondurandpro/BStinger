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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.slashed
import org.example.kotlineditor.syntax.treesitter.GrammarStore

private val INDEXED_POPUPS = setOf(PopupKind.GotoFile, PopupKind.FindInFiles, PopupKind.GotoSymbol)
private val DOCUMENT_POPUPS = setOf(PopupKind.GotoLine, PopupKind.FileStructure)

/** An open project: its tree, editors, index, terminal and tool windows. See `ProjectActions.kt` for actions. */
@Stable
@Suppress("TooManyFunctions")
class ProjectState(
    override val root: FileEntry,
    override val services: AppServices,
    parentScope: CoroutineScope,
    preferences: Map<String, String> = emptyMap(),
    /** Open tool windows and their docks; a worktree of agent mode shares the one of its project. */
    override val toolWindows: ToolWindowLayout = ToolWindowLayout().apply { restore(preferences) },
    /** Tree-sitter grammars, shared with the worktrees agent mode opens from this project. */
    internal val grammars: GrammarStore? = null,
    /** Language servers of the IDE, shared by every project; none for the worktrees of agent mode. */
    override val languageServers: LanguageServersState? = null,
    /** The command line of the agent of agent mode, as set in the settings. */
    private val agentCommand: () -> AgentCommand = { agentCommandOf("") },
    /** The layout of the diffs, saved; a worktree of agent mode shares the one of its project. */
    internal val diffLayout: DiffLayout = DiffLayout(preferences),
) : GitHost,
    TreeHost,
    LanguageServersHost,
    AgentHost {
    /** Cancelled when the project is closed, so no background work outlives it. */
    override val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
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
    val findInFiles =
        FindInFilesSearch(
            root,
            services.search,
            fileIndex,
            scope,
            UnsavedDocumentsSearch(fileIndex::relativePath),
            documents = { editor.unsavedDocuments() },
        )
    val find = FindState()

    /** Code completion in the editors, from the language servers. */
    val completion =
        CompletionState(
            scope,
            resolve = { token, index -> languageServers?.resolveCompletion(token, index) },
        ) { path, document -> languageServers?.complete(path, document) ?: CompletionResult.NotReady }

    /** Told when an agent finishes a turn (the window shows it as a notification when it is in the background). */
    var finishedListener: ((String, Boolean) -> Unit)? = null

    /** Told when an agent asks for a permission (a notification when the window is in the background). */
    var askingListener: ((String, String) -> Unit)? = null

    /** Opens a web address in the browser of the machine of the IDE; tests replace it. */
    var openUrl: (String) -> Unit = ::openInBrowser

    /** Hover, navigation, usages, problems, rename, reformat and quick fixes, from the language servers. */
    val insight = CodeInsightState(scope, { insightBackend() }, ProjectInsightHost { this })
    val terminal = TerminalState(root.path, services.terminal, scope)

    /** Run configurations, the output of their commands and the tree of test results. */
    val run =
        RunState(
            root.path,
            services.runner,
            scope,
            preferences,
            suggest = ::suggestedRunConfigurations,
            locate = ::locateOutputFile,
            openAt = ::openOutputFile,
        )

    /** Git: branch, status, commit, branches of the toolbar, and the Branches tool window's history. */
    override val git =
        GitState(
            root,
            services.git,
            scope,
            editor,
            synchronize = ::synchronize,
            pinned = pinnedBranchesOf(preferences, root.path),
        )
    val network = NetworkView(services.network)

    // Created on first use: the worktrees of the conversations are projects too, without agent mode of their own.
    private val agentState = lazy { AgentState(this, agentCommand, preferences) }

    /** Conversations of agent mode, in memory, each on the current branch or in a worktree of its own. */
    val agent by agentState

    override val gitStatus: Map<String, GitFileStatus> get() = git.status

    /** True when the window shows agent mode instead of the editors (toolbar switch, Ctrl+Alt+A). */
    override var agentMode by mutableStateOf(false)
    override val checkedOutBranch: String? get() = git.currentBranch
    var popup: PopupKind? by mutableStateOf(null)
    override var fileDialog: FileDialog? by mutableStateOf(null)

    /** "Always Select Opened File" (IntelliJ): the active file is selected and revealed in the project tree. */
    override var autoSelectOpenedFile by mutableStateOf(autoSelectOf(preferences))

    override var inlayHintsMode: InlayHintsMode
        get() = insight.inlayHintsMode
        set(value) {
            insight.inlayHintsMode = value
        }

    init {
        insight.inlayHintsMode = InlayHintsMode.fromSaved(preferences[PreferenceKeys.INLAY_HINTS])
    }

    /** True once the server watches the disk of the project for this window (see `followDisk`). */
    var diskWatched by mutableStateOf(false)
        internal set

    /** Diff viewer layout: old and new files side by side (two panes) instead of the unified view. */
    var diffSideBySide: Boolean
        get() = diffLayout.sideBySide
        set(value) {
            diffLayout.sideBySide = value
        }

    /** Positions left by navigation actions, for Ctrl+Alt+Left (Back). */
    internal val backStack = ArrayDeque<Pair<FileEntry, TextPos>>()

    init {
        git.refresh()
        scope.launch { followDisk() }
        followActiveFile()
        followInsight()
    }

    /** What to save of agent mode; nothing while it was never used in this project (the saved values stay). */
    fun agentPreferences(): Map<String, String> = if (agentState.isInitialized()) agent.toPreferences() else emptyMap()

    // The configurations a project suggests the first time the Run window opens, from the files at its root.
    private suspend fun suggestedRunConfigurations(): List<RunConfiguration> {
        val entries = runCatching { services.fileSystem.list(root) }.getOrDefault(emptyList())
        val windows = !root.path.startsWith("/")
        return suggestedConfigurations(entries.map { it.name }.toSet(), windows)
    }

    // The file a path of some output names: an absolute path, a path from the project, or only a file name.
    private suspend fun locateOutputFile(path: String): FileEntry? {
        val direct = runCatching { services.fileSystem.fileAt(path) }.getOrNull()
        if (direct != null) return direct
        val fromRoot = root.path.trimEnd('/', '\\') + "/" + path.removePrefix("./")
        val inProject = runCatching { services.fileSystem.fileAt(fromRoot) }.getOrNull()
        if (inProject != null) return inProject
        fileIndex.ensureLoaded()
        val files = snapshotFlow { fileIndex.files }.filterNotNull().first()
        val wanted = slashed(path).trimStart('/')
        return files.firstOrNull { slashed(it.path, root.path).endsWith("/$wanted") }
    }

    // Output counts lines and columns from 1; the editor from 0.
    private fun openOutputFile(entry: FileEntry, line: Int, column: Int) {
        navigate(entry, TextPos(maxOf(line - 1, 0), maxOf(column - 1, 0)), TextPos(maxOf(line - 1, 0), maxOf(column - 1, 0)))
    }

    /** Shows or hides a tool window (Alt+1, Alt+9, Alt+F12, Ctrl+Alt+Shift+D or its button in the left strip). */
    fun toggleToolWindow(window: ToolWindow) {
        toolWindows.toggle(window)
        if (window == ToolWindow.Terminal && toolWindows.isVisible(window)) terminal.ensureStarted()
        if (window == ToolWindow.Run && toolWindows.isVisible(window)) run.ensureLoaded()
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
        val date = localDate(commit.time)
        val header = CommitHeader(commit.author, date, commit.subject)
        val target = CommitDiffTarget(commit.hash, commit.shortHash, file.oldPath, header)
        editor.openCommitDiff(entryAt(file.path), target)
    }

    /**
     * A click on the blame annotation of [line] in [tab]: the diff of the file in the commit that last changed the
     * line, its whole message above. Lines not committed yet have no commit to show.
     */
    fun openBlameCommit(tab: EditorTab, line: BlameLine) {
        if (line.commit.all { it == '0' }) return
        scope.launch {
            val details = runCatching { services.git.commitDetails(root.path, line.commit) }.getOrNull()
            if (details != null && details.hash.isNotEmpty()) openCommitOf(tab, line, details)
        }
    }

    private fun openCommitOf(tab: EditorTab, line: BlameLine, details: GitCommitDetails) {
        run {
            val relative = tree.relativePath(tab.entry)
            val file = details.files.firstOrNull { it.path == relative }
            val header = CommitHeader(line.author, line.date, details.message)
            val target =
                CommitDiffTarget(
                    details.hash,
                    GitCommit(details.hash, emptyList(), line.author, 0, "").shortHash,
                    file?.oldPath,
                    header,
                )
            editor.openCommitDiff(tab.entry, target)
        }
    }

    /** Opens [entry] at [start]..[end], remembering where we came from. */
    override fun navigate(entry: FileEntry, start: TextPos, end: TextPos) {
        val tab = editor.activeTab
        val document = tab?.document
        if (tab != null && document != null) backStack.addLast(tab.entry to document.caret)
        if (backStack.size > MAX_BACK_STEPS) backStack.removeFirst()
        editor.openAt(entry, start, end)
    }

    override suspend fun serverOutline(document: Document) = outlineFromServer(document)

    override suspend fun serverSymbols(query: String) = symbolsFromServers(query)

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

    override suspend fun openWorktree(branch: String): AgentWorkspace = openWorktreeProject(branch)

    override fun agentAsking(title: String, question: String) {
        askingListener?.invoke(title, question)
    }

    override fun agentFinished(title: String, failed: Boolean) {
        finishedListener?.invoke(title, failed)
    }

    override fun synchronize() = synchronizeFiles()

    override fun dispose() {
        if (agentState.isInitialized()) agent.dispose()
        terminal.stop()
        run.dispose()
        scope.cancel()
    }
}

// `yyyy-MM-dd` of a commit time (seconds since the epoch), in the local time zone.
private fun localDate(seconds: Long): String {
    val instant = java.time.Instant.ofEpochSecond(seconds)
    return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}

/** "Always Select Opened File" as saved in [preferences]: on when it was never changed. */
internal fun autoSelectOf(preferences: Map<String, String>): Boolean =
    preferences[PreferenceKeys.AUTO_SELECT_OPENED_FILE]?.toBooleanStrictOrNull() ?: true
