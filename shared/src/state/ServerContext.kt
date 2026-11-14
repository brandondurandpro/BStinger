package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import java.io.IOException

/**
 * Everything the IDE shows of one server, the embedded one or a remote one: its services, its language servers, its
 * recent projects, the project open there and the Open Project dialog that browses its disk. A switch of server
 * replaces the whole context, never part of it: [close] stops all its work, so nothing keeps talking to a server the
 * IDE left.
 */
@Stable
@Suppress("TooManyFunctions")
class ServerContext(
    val services: AppServices,
    /** The SSH tunnel to a remote server; null for the local server, or a server given on the command line. */
    private val tunnel: RemoteConnection?,
    /** The IDE's scope: saves started here outlive the context. */
    private val appScope: CoroutineScope,
    private val preferences: PreferenceHub,
    /** Tree-sitter grammars; without them, files are colored by the line lexer only. */
    private val grammars: GrammarStore?,
    /** The agent of agent mode, as set in the settings. */
    private val agentCommand: () -> AgentCommand,
    /** Whether a project opens in agent mode (the user's default mode, when agent mode is on). */
    private val startsInAgentMode: () -> Boolean = { false },
    /** What the server is told about the telemetry. */
    private val consent: () -> SharedConsent,
    /** A project was opened on this remote server (never called for a local one). */
    private val onRemoteProjectOpened: (path: String) -> Unit,
    /** How to open a project in another IDE window, asked each time as the desktop app sets it late; null: none. */
    private val newWindow: () -> ((path: String) -> Unit)? = { null },
) : ProjectPickerHost {
    // Cancelled by close(): the loops, the requests and the projects of this server.
    private val jobs = SupervisorJob(appScope.coroutineContext[Job])
    private val scope = appScope + jobs

    /** The language servers of this server, and the choice of a server for a language. */
    val languageServers = LanguageServersState(services.languageServers, scope, services.connections)

    var screen: Screen by mutableStateOf(Screen.Welcome)
        private set

    val recentProjects = mutableStateListOf<String>()

    /** Home directory of the server, where the Open Project dialog starts; null until it is known. */
    var home: String? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** The project being opened (its directory resolved on the server), shown until it opens or fails. */
    var opening: String? by mutableStateOf(null)
        private set

    /** Whether the Open Project dialog is open (from the welcome screen or the project menu). */
    var projectPickerOpen: Boolean by mutableStateOf(false)
        private set

    /** Whether the Open Project dialog picks the project of a new conversation, opened in another window. */
    override var projectPickerForNewWindow: Boolean by mutableStateOf(false)
        private set

    // Only the latest request opens its project: a late answer must not replace a project chosen after it.
    private var openRequest = 0

    /** The project shown, if any. */
    val project: ProjectState? get() = (screen as? Screen.Project)?.project

    /** The recent projects other than the open one, most recent first: those the project menu switches to. */
    val otherProjects: List<String>
        get() {
            val current = project?.root?.path
            return recentProjects.filter { it != current }
        }

    /** The link with the server (reconnecting after a network failure, ...). */
    val connection get() = services.connection

    /** Whether this server is reached through SSH. */
    val isRemote: Boolean get() = tunnel != null

    /** `user@host` of the remote server, or null when the server is local. */
    override val remoteLabel: String? get() = tunnel?.label

    /** Home directory of the SSH user, or null when the server is local. */
    val remoteHome: String? get() = tunnel?.home

    /** Why the remote SSH tunnel refuses to connect, or null. */
    val remoteFailure: StateFlow<String?> = tunnel?.failure ?: MutableStateFlow(null)

    init {
        scope.launch { declareOpenFiles() }
        scope.launch { syncOpenDocuments() }
        scope.launch { shareTelemetryConsent() }
    }

    /** Loads the recent projects and the home of this server, in the background. */
    fun reloadRecents() {
        scope.launch { loadRecents() }
    }

    /** Loads the recent projects and the home of this server. */
    suspend fun loadRecents() {
        val paths = runCatching { services.recentProjects.load() }.getOrDefault(emptyList())
        recentProjects.clear()
        recentProjects.addAll(paths)
        // The Open Project dialog starts there.
        home = runCatching { services.places.home() }.getOrNull()
    }

    fun openProject(path: String) {
        val request = ++openRequest
        error = null
        opening = path
        scope.launch {
            // From the click to the frame that shows the project.
            ClientTelemetry.measureToFrame(Operation.ProjectOpen, locationAttributes()) { openNow(path, request) }
        }
    }

    private suspend fun openNow(path: String, request: Int) {
        val root =
            try {
                services.fileSystem.resolveDirectory(path) ?: run {
                    if (request == openRequest) error = "Directory not found: $path"
                    null
                }
            } catch (e: IOException) {
                if (request == openRequest) error = "Cannot reach the server: ${e.message}"
                null
            }
        if (request != openRequest) return
        if (root == null) {
            opening = null
            return
        }
        open(root, request)
    }

    private fun locationAttributes(): Attributes {
        val location = if (isRemote) "remote" else "local"
        return Attributes.of(Attribute.Location to location)
    }

    private suspend fun open(root: FileEntry, request: Int) {
        error = null
        recentProjects.remove(root.path)
        recentProjects.add(0, root.path)
        while (recentProjects.size > MAX_RECENT) recentProjects.removeAt(recentProjects.lastIndex)
        saveRecent()
        if (isRemote) onRemoteProjectOpened(root.path)
        project?.let { saveAndDispose(it) }
        val opened =
            ProjectState(
                root,
                services,
                scope,
                preferences.all,
                grammars = grammars,
                languageServers = languageServers,
                agentCommand = agentCommand,
                windows = windowsForProjects(),
            )
        // "Opening…" stays shown meanwhile: the project then appears with its tabs and tree as they were left.
        opened.restoreSession(RESTORE_WAIT_MILLIS)
        if (request != openRequest) {
            // Closed, or another project opened, while the session was restored.
            opened.dispose()
            return
        }
        opened.agentMode = startsInAgentMode()
        opening = null
        // The worktrees of agent mode share this repository: only the opened project fetches its remotes.
        opened.git.startAutoFetch()
        screen = Screen.Project(opened)
    }

    // A window added by the desktop app reaches the server it started with, not a remote one: paths mean nothing there.
    private fun windowsForProjects(): ProjectWindows? {
        if (isRemote) return null
        val others = { if (newWindow() == null) emptyList() else otherProjects }
        val available = { newWindow() != null }
        return ProjectWindows(available, others, { path -> newWindow()?.invoke(path) }, ::browseForProjectInNewWindow)
    }

    fun removeRecent(path: String) {
        recentProjects.remove(path)
        saveRecent()
    }

    fun closeProject() {
        openRequest++
        opening = null
        project?.let { closed -> appScope.launch { saveAndDispose(closed) } }
        screen = Screen.Welcome
    }

    /** Opens the Open Project dialog, which browses the directories of this server's disk. */
    fun browseForProject() {
        projectPickerOpen = true
    }

    /** Opens the Open Project dialog for a new conversation: the directory chosen opens in another window. */
    fun browseForProjectInNewWindow() {
        projectPickerForNewWindow = true
        projectPickerOpen = true
    }

    /** Closes the Open Project dialog without opening anything (its Cancel, Escape or the backdrop). */
    override fun closeProjectPicker() {
        projectPickerOpen = false
        projectPickerForNewWindow = false
    }

    /** Opens [path] as the project of a confirmed Open Project dialog, in another window for a new conversation. */
    override fun openFromProjectPicker(path: String) {
        val inNewWindow = projectPickerForNewWindow
        closeProjectPicker()
        if (inNewWindow) newWindow()?.invoke(path) else openProject(path)
    }

    /** Creates [name] in [parent] for the Open Project dialog. */
    override suspend fun createPickerDirectory(parent: FileEntry, name: String): FileEntry =
        services.fileSystem.createDirectory(parent, name)

    /** Resolves [path] on the server's disk to a directory, or `null` when there is no such directory. */
    override suspend fun resolvePickerDirectory(path: String): FileEntry? = services.fileSystem.resolveDirectory(path)

    /** The directories inside [directory], sorted as the server lists them (directories first, by name). */
    override suspend fun pickerSubdirectories(directory: FileEntry): List<FileEntry> =
        services.fileSystem.list(directory).filter { it.isDirectory }

    /** Remembers [path] as the last directory the Open Project dialog browsed (a preference of the local server). */
    override fun rememberProjectPickerDirectory(path: String) {
        // A remote path means nothing on the local disk, and the remote dialog starts from the project or home.
        if (isRemote || path == preferences[PreferenceKeys.PICKER_DIRECTORY]) return
        preferences.putQuietly(PreferenceKeys.PICKER_DIRECTORY, path)
    }

    /**
     * Where the Open Project dialog starts: on a remote server the open project, else the SSH user's home; on the
     * local one the last directory it browsed, else the home; the root when nothing is known.
     */
    override fun projectPickerStart(): String {
        if (!isRemote) return preferences[PreferenceKeys.PICKER_DIRECTORY] ?: home ?: "/"
        return project?.root?.path ?: projectPickerHome() ?: "/"
    }

    /** The home directory of the server (the SSH user's on a remote one): the dialog's Home and `~`. */
    override fun projectPickerHome(): String? = if (isRemote) remoteHome ?: home else home

    /** The preferences of the open project: its tool windows, its settings and its pinned branches. */
    fun toPreferences(): Map<String, String> {
        val shown = project ?: return emptyMap()
        val autoSelect = PreferenceKeys.AUTO_SELECT_OPENED_FILE to shown.autoSelectOpenedFile.toString()
        val inlayHints = PreferenceKeys.INLAY_HINTS to shown.inlayHintsMode.name
        val pins = shown.git.pinnedBranchesPreference()
        val windows = shown.toolWindows.toPreferences()
        val agent = shown.agentPreferences()
        val diffs = shown.diffLayout.toPreferences()
        val runs = shown.run.toPreferences()
        val indicators = shown.statusIndicators.toPreferences()
        return windows + autoSelect + inlayHints + pins + agent + diffs + runs + indicators
    }

    /** Saves the open project's session and files, then disposes it; it stays shown until [leave]. */
    suspend fun saveOpenProject() {
        project?.let { saveAndDispose(it) }
    }

    /** Before the IDE shows another server: saves the open project and comes back to the welcome screen. */
    suspend fun leave() {
        openRequest++
        opening = null
        projectPickerOpen = false
        saveOpenProject()
        screen = Screen.Welcome
    }

    /** Stops everything this context does with its server, and its SSH tunnel (the remote daemon keeps running). */
    fun close() {
        projectPickerOpen = false
        languageServers.close()
        jobs.cancel()
        tunnel?.close()
    }

    private fun saveRecent() {
        val recent = recentProjects.toList()
        val store = services.recentProjects
        appScope.launch { runCatching { store.save(recent) } }
    }

    private suspend fun saveAndDispose(project: ProjectState) {
        val session = project.session()
        runCatching { project.services.sessions.save(project.root.path, session) }
        runCatching { project.editor.flush() }
        project.dispose()
    }

    // The server starts the language servers the open files need, and stops them when they are closed.
    private suspend fun declareOpenFiles() {
        snapshotFlow {
            val shown = project
            val files =
                shown
                    ?.editor
                    ?.allTabs
                    ?.filterNot { it.isDiff }
                    ?.map { it.entry.path }
                    .orEmpty()
            shown?.root?.path to files
        }.collect { (projectPath, files) -> languageServers.declare(projectPath, files) }
    }

    // The documents of the open files are mirrored on the server, for completion and the other language features.
    private suspend fun syncOpenDocuments() {
        snapshotFlow {
            val shown = project
            val documents =
                shown
                    ?.editor
                    ?.allTabs
                    ?.filterNot { it.isDiff }
                    ?.mapNotNull { tab -> tab.document?.let { tab.entry.path to it } }
                    ?.toMap()
                    .orEmpty()
            shown?.root?.path to documents
        }.collect { (projectPath, documents) -> languageServers.syncDocuments(projectPath, documents) }
    }

    // The server measures only when the user agreed: it is told on each connection (a restarted server forgot it)
    // and each time the user changes their mind.
    private suspend fun shareTelemetryConsent() {
        snapshotFlow { consent() }.collectLatest { shared ->
            services.connections.filter { it > 0 }.collect { tellServer(shared) }
        }
    }

    private suspend fun tellServer(shared: SharedConsent) {
        try {
            services.telemetry.configure(shared.enabled, shared.instanceId)
        } catch (_: IOException) {
            // The link broke again (told on the next connection), or a server older than the telemetry refused.
        }
    }

    private companion object {
        const val MAX_RECENT = 20

        // How long opening a project waits for its session before showing it anyway (a slow or remote server).
        const val RESTORE_WAIT_MILLIS = 1_000L
    }
}
