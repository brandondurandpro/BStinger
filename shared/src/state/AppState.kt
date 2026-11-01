package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.remote.SshTarget
import java.io.IOException

/** The whole IDE: the welcome screen or the open project, plus IDE-wide settings. */
@Stable
@Suppress("TooManyFunctions")
class AppState(
    initialServices: AppServices,
    private val scope: CoroutineScope,
    private val preferenceStore: PreferenceStore = InMemoryPreferenceStore(),
    /** Services of the local server, restored when a remote session ends; null when the IDE started remotely. */
    private val localServices: AppServices? = initialServices,
    /** Creates SSH sessions; null in previews and tests that do not exercise remote development. */
    private val remoteFactory: RemoteFactory? = null,
) {
    // Last preferences known: those of the open project are read live, these cover the welcome screen.
    private var preferences: Map<String, String> = preferenceStore.load()

    /** Services of the server currently shown: the embedded one, or a remote one. */
    var services: AppServices = initialServices
        private set

    var screen: Screen by mutableStateOf(Screen.Welcome)
        private set

    val recentProjects = mutableStateListOf<String>()

    /** The "Remote Development" dialog, when open. */
    var remoteDialog: RemoteDialogState? by mutableStateOf(null)
        private set

    /** The remote folder picker, when open. */
    var folderPicker: FolderPickerState? by mutableStateOf(null)
        private set

    /** Hosts of the client's `~/.ssh/config`, shown in the remote dialog. */
    var sshHosts: List<SshConfigHost> by mutableStateOf(emptyList())
        private set

    /** Whether `~/.ssh/config` is being read (it resolves each alias with `ssh -G`). */
    var sshHostsLoading: Boolean by mutableStateOf(false)
        private set

    /** Remote folders already opened, newest first, searchable from the remote dialog. */
    val remoteWorkspaces = mutableStateListOf<RemoteWorkspace>()

    /** What the remote connection is doing, while it is being established. */
    var remoteStatus: String? by mutableStateOf(null)
        private set

    private var remoteSession: RemoteConnection? = null
    private var remoteJob: Job? = null

    private var lastRemote: SshTarget? by mutableStateOf(RemotePreferenceKeys.read(preferences))

    /** Whether the IDE shows a project on a remote server. */
    val isRemote: Boolean get() = remoteSession != null

    /** `user@host` of the remote session, or null when the server is local. */
    val remoteLabel: String? get() = remoteSession?.label

    /** Home directory of the SSH user, or null when the server is local. */
    val remoteHome: String? get() = remoteSession?.home

    /** Why the remote SSH tunnel refuses to connect, or null. */
    val remoteFailure: StateFlow<String?> get() = remoteSession?.failure ?: noRemoteFailure

    private val noRemoteFailure = MutableStateFlow<String?>(null)

    /** The link with the server (reconnecting after a network failure, ...). */
    val connection get() = services.connection

    var error: String? by mutableStateOf(null)
        private set

    /** Whether the devtools were opened (Ctrl+Alt+Shift+D) since the IDE started: shows their buttons. */
    var devtools by mutableStateOf(false)

    /** Editor font size in points, shared by every editor (Alt+Shift+. / Alt+Shift+, / Ctrl+wheel). */
    var editorFontSize by mutableFloatStateOf(savedFontSize())
        private set

    val title: String
        get() =
            when (val s = screen) {
                Screen.Welcome -> {
                    "Welcome to Kotlin Editor"
                }

                is Screen.Project -> {
                    s.project.editor.activeTab
                        ?.let { "${s.project.name} – ${it.entry.name}" }
                        ?: s.project.name
                }
            }

    init {
        remoteWorkspaces.addAll(
            RemoteWorkspaceCodec.decode(preferences[RemotePreferenceKeys.WORKSPACES].orEmpty()),
        )
        scope.launch { savePreferencesOnChange() }
        reloadRecents()
    }

    /** Opens the "Remote Development" dialog, prefilled with the last connection and the SSH catalog. */
    fun openRemoteDialog() {
        remoteDialog = RemoteDialogState.from(lastRemote)
        loadSshHosts()
    }

    private fun loadSshHosts() {
        val factory = remoteFactory
        if (factory == null) {
            sshHosts = emptyList()
            return
        }
        sshHostsLoading = true
        scope.launch {
            sshHosts = withContext(Dispatchers.IO) { runCatching { factory.sshHosts() }.getOrDefault(emptyList()) }
            sshHostsLoading = false
        }
    }

    /** A host of `~/.ssh/config` was picked: use the alias, and let ssh resolve user, port and key. */
    fun selectSshHost(host: SshConfigHost) {
        val dialog = remoteDialog ?: return
        remoteDialog =
            dialog.copy(
                alias = host.alias,
                host = host.alias,
                user = host.user.orEmpty(),
                port = host.port.toString(),
                identityFile = "",
                projectPath = "",
                error = null,
            )
    }

    /** A saved remote folder was picked: fill the connection and its path, ready to connect. */
    fun selectRemoteWorkspace(workspace: RemoteWorkspace) {
        val dialog = remoteDialog ?: return
        remoteDialog =
            dialog.copy(
                alias = workspace.target.alias.orEmpty(),
                host = workspace.target.host,
                user = workspace.target.user.orEmpty(),
                port = workspace.target.port.toString(),
                identityFile = workspace.target.identityFile.orEmpty(),
                projectPath = workspace.path,
                error = null,
            )
    }

    /** Updates the dialog while the user types. */
    fun updateRemoteDialog(dialog: RemoteDialogState) {
        remoteDialog = dialog
    }

    /** Closes the dialog and cancels a connection in progress. */
    fun dismissRemoteDialog() {
        remoteJob?.cancel()
        remoteJob = null
        remoteDialog = null
        remoteStatus = null
    }

    /**
     * Installs the server on [target], opens the SSH tunnel and switches the IDE to it. The project, when given,
     * opens once the connection is ready; otherwise the remote welcome screen shows its recent projects.
     */
    @Suppress("TooGenericExceptionCaught") // every failure of the SSH flow belongs in the dialog
    fun connectRemote(dialog: RemoteDialogState) {
        val factory =
            remoteFactory ?: run {
                remoteDialog = dialog.copy(error = "Remote development is not available in this build")
                return
            }
        val target =
            dialog.toSshTarget().getOrElse {
                remoteDialog = dialog.copy(error = it.message)
                return
            }
        remoteJob?.cancel()
        remoteDialog = dialog.copy(connecting = true, error = null)
        remoteStatus = "Checking ${target.destination}"
        remoteJob =
            scope.launch {
                try {
                    val session =
                        withContext(Dispatchers.IO) {
                            factory.connect(target) { step -> scope.launch { remoteStatus = step } }
                        }
                    // Cancelled while a blocking SSH command was running: do not leak the tunnel.
                    if (isActive) {
                        switchTo(session, target)
                    } else {
                        session.close()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    remoteStatus = null
                    remoteDialog = dialog.copy(error = e.message ?: "Cannot connect to ${target.destination}")
                }
            }
    }

    /** Releases the remote tunnel when the IDE window goes away without [exit] (tests and previews). */
    fun dispose() {
        remoteJob?.cancel()
        remoteJob = null
        closeFolderPicker()
        remoteSession?.close()
        remoteSession = null
    }

    /** Saves the open project, stops the remote session (its daemon keeps running) and shows the local server. */
    fun disconnectRemote() {
        val local = localServices ?: return
        remoteJob?.cancel()
        remoteJob = null
        remoteDialog = null
        remoteStatus = null
        closeFolderPicker()
        scope.launch {
            (screen as? Screen.Project)?.project?.let { saveAndDispose(it) }
            remoteSession?.close()
            remoteSession = null
            services = local
            screen = Screen.Welcome
            reloadRecents()
        }
    }

    private suspend fun switchTo(session: RemoteConnection, target: SshTarget) {
        (screen as? Screen.Project)?.project?.let { saveAndDispose(it) }
        remoteSession?.close()
        remoteSession = session
        services = session.services
        lastRemote = target
        remoteDialog = null
        remoteStatus = null
        screen = Screen.Welcome
        // Load before opening the project, so its own addition to the list is not overwritten by the load.
        loadRecentsNow()
        if (target.projectPath != null) openProject(target.projectPath) else browseForProject()
    }

    private fun reloadRecents() {
        scope.launch { loadRecentsNow() }
    }

    private suspend fun loadRecentsNow() {
        val paths = runCatching { services.recentProjects.load() }.getOrDefault(emptyList())
        recentProjects.clear()
        recentProjects.addAll(paths)
    }

    fun zoomEditor(delta: Float) {
        editorFontSize = (editorFontSize + delta).coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
    }

    fun resetEditorZoom() {
        editorFontSize = DEFAULT_EDITOR_FONT_SIZE
    }

    fun openProject(path: String) {
        scope.launch {
            val root =
                try {
                    val found = services.fileSystem.resolveDirectory(path)
                    if (found == null) error = "Directory not found: $path"
                    found
                } catch (e: IOException) {
                    error = "Cannot reach the server: ${e.message}"
                    null
                }
            root?.let { open(it) }
        }
    }

    /**
     * Opens a project: the native chooser for the local server, or the remote folder browser (starting in the SSH
     * user's home, or in the open project) for a remote one.
     */
    fun browseForProject() {
        if (isRemote) {
            closeFolderPicker()
            val start = (screen as? Screen.Project)?.project?.root?.path ?: remoteHome ?: "/"
            folderPicker = FolderPickerState(services.fileSystem, scope, start, remoteHome)
        } else {
            scope.launch { services.directoryPicker.pickDirectory()?.let(::openProject) }
        }
    }

    fun closeFolderPicker() {
        folderPicker?.dispose()
        folderPicker = null
    }

    fun openPickedFolder(path: String) {
        closeFolderPicker()
        openProject(path)
    }

    fun removeRecent(path: String) {
        recentProjects.remove(path)
        saveRecent()
    }

    fun closeProject() {
        (screen as? Screen.Project)?.project?.let { close(it) }
        screen = Screen.Welcome
    }

    /** Saves everything, stops the remote session, then calls [onDone] (used when the window is closed). */
    fun exit(onDone: () -> Unit) {
        closeFolderPicker()
        scope.launch {
            (screen as? Screen.Project)?.project?.let { saveAndDispose(it) }
            runCatching { preferenceStore.save(currentPreferences()) }
            remoteSession?.close()
            remoteSession = null
            onDone()
        }
    }

    private fun savedFontSize(): Float =
        preferences[PreferenceKeys.EDITOR_FONT_SIZE]
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
            ?: DEFAULT_EDITOR_FONT_SIZE

    // The open project's settings on top of the last known preferences.
    private fun currentPreferences(): Map<String, String> {
        val project = (screen as? Screen.Project)?.project
        val projectPreferences =
            if (project == null) {
                emptyMap()
            } else {
                project.toolWindows.toPreferences() +
                    (PreferenceKeys.AUTO_SELECT_OPENED_FILE to project.autoSelectOpenedFile.toString())
            }
        return preferences + projectPreferences + fontSizePreference() + remotePreferences()
    }

    private fun remotePreferences(): Map<String, String> =
        lastRemote?.let(RemotePreferenceKeys::write).orEmpty() +
            (RemotePreferenceKeys.WORKSPACES to RemoteWorkspaceCodec.encode(remoteWorkspaces))

    private fun fontSizePreference() = PreferenceKeys.EDITOR_FONT_SIZE to editorFontSize.toString()

    // Writes the preferences each time one of them changes, off the UI thread.
    private suspend fun savePreferencesOnChange() {
        snapshotFlow { currentPreferences() }.drop(1).collect {
            preferences = it
            withContext(Dispatchers.IO) { runCatching { preferenceStore.save(it) } }
        }
    }

    private suspend fun open(root: FileEntry) {
        error = null
        recentProjects.remove(root.path)
        recentProjects.add(0, root.path)
        while (recentProjects.size > MAX_RECENT) recentProjects.removeAt(recentProjects.lastIndex)
        saveRecent()
        recordRemoteWorkspace(root.path)
        (screen as? Screen.Project)?.project?.let { saveAndDispose(it) }
        val project = ProjectState(root, services, scope, preferences)
        runCatching { services.sessions.load(root.path) }.getOrNull()?.let(project::restoreSession)
        screen = Screen.Project(project)
    }

    private fun recordRemoteWorkspace(path: String) {
        if (!isRemote) return
        val target = lastRemote?.copy(projectPath = null) ?: return
        remoteWorkspaces.removeAll { it.matches(target, path) }
        remoteWorkspaces.add(0, RemoteWorkspace(target, path, System.currentTimeMillis()))
        while (remoteWorkspaces.size > RemoteWorkspaceCodec.MAX) {
            remoteWorkspaces.removeAt(remoteWorkspaces.lastIndex)
        }
    }

    private fun saveRecent() {
        val recent = recentProjects.toList()
        val store = services.recentProjects
        scope.launch { runCatching { store.save(recent) } }
    }

    private fun close(project: ProjectState) {
        scope.launch { saveAndDispose(project) }
    }

    // Saves through the project's own services: the IDE may switch to another server right after.
    private suspend fun saveAndDispose(project: ProjectState) {
        val session = project.session()
        runCatching { project.services.sessions.save(project.root.path, session) }
        runCatching { project.editor.flush() }
        project.dispose()
    }

    companion object {
        private const val MAX_RECENT = 20
        const val DEFAULT_EDITOR_FONT_SIZE = 13f
        const val MIN_EDITOR_FONT_SIZE = 8f
        const val MAX_EDITOR_FONT_SIZE = 40f
    }
}
