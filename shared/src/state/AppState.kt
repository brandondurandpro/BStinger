package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.telemetry.Telemetry

/**
 * The whole IDE: the settings and the remote development, which do not depend on a server, and the [session] of the
 * server shown, replaced as a whole when the IDE switches to another server.
 */
@Stable
class AppState(
    initialServices: AppServices,
    private val scope: CoroutineScope,
    preferenceStore: PreferenceStore = InMemoryPreferenceStore(),
    /** Tree-sitter grammars; without them, files are colored by the line lexer only. */
    private val grammars: GrammarStore? = null,
    /** Services of the local server, restored when a remote session ends; null when the IDE started remotely. */
    private val localServices: AppServices? = initialServices,
    /** Creates SSH sessions; null in previews and tests that do not exercise remote development. */
    remoteFactory: RemoteFactory? = null,
    /** Makes the telemetry the user agrees to, for an installation id; null measures nothing (previews, tests). */
    telemetryOf: ((instanceId: String) -> Telemetry)? = null,
) {
    val preferences = PreferenceHub(preferenceStore)

    val settings =
        SettingsController(preferences, scope, { session.project }, { session.languageServers }, telemetryOf)

    val remote = RemoteController(preferences, scope, remoteFactory, ::showRemote)

    /** The server shown: its services, language servers, recent projects and open project. */
    var session: ServerContext by mutableStateOf(contextOf(initialServices, tunnel = null))
        private set

    /** Whether the devtools were opened (Ctrl+Alt+Shift+D) since the IDE started: shows their buttons. */
    var devtools by mutableStateOf(false)

    // One switch of server at a time: a disconnection waits for the connection being shown.
    private val switching = Mutex()

    /** Closing the IDE: the files it could not save, until the user retries, closes anyway or keeps it open. */
    val exiting = AppExit(scope, { session.project }, ::finishExit)

    /** Whether a dialog of the whole window is open (Open Project, Settings): it keeps the keyboard. */
    val dialogOpen: Boolean get() = session.projectPickerOpen || settings.dialog != null

    val title: String
        get() =
            when (val s = session.screen) {
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
        scope.launch { preferences.saveOnChange(::currentPreferences) }
        session.reloadRecents()
    }

    /** Saves the open project, stops the remote session (its daemon keeps running) and shows the local server. */
    fun disconnectRemote() {
        val local = localServices ?: return
        remote.dismissRemoteDialog()
        session.closeProjectPicker()
        scope.launch { show(local, tunnel = null) }
    }

    /** Releases the remote tunnel when the IDE window goes away without [exit] (tests and previews). */
    fun dispose() {
        remote.dismissRemoteDialog()
        session.close()
    }

    /**
     * Saves everything, stops the remote session, then calls [onDone] (used when the window is closed); the files
     * that could not be saved are shown first, until the user chooses (see [AppExit]).
     */
    fun exit(onDone: () -> Unit) {
        session.closeProjectPicker()
        exiting.start(onDone)
    }

    // What closing the IDE does once the files are saved, or the user chose to close without them.
    private suspend fun finishExit() {
        switching.withLock {
            val shown = session
            shown.project?.dispose()
            // Read while the project is still shown: its own preferences are saved with the others.
            preferences.saveNow(::currentPreferences)
            shown.close()
        }
    }

    // A connection was established: its server is shown, then the project asked for, else the Open Project dialog.
    private suspend fun showRemote(tunnel: RemoteConnection, target: SshTarget) {
        val shown = show(tunnel.services, tunnel) { remote.connectionShown(target) }
        val projectPath = target.projectPath
        if (projectPath != null) shown.openProject(projectPath) else shown.browseForProject()
    }

    // The previous server saves its project and stops, then the new one appears with its recent projects already
    // loaded (a path of the previous server listed there would be opened on this one).
    private suspend fun show(
        services: AppServices,
        tunnel: RemoteConnection?,
        onShown: () -> Unit = {},
    ): ServerContext =
        switching.withLock {
            val previous = session
            val next = contextOf(services, tunnel)
            previous.leave()
            previous.close()
            next.loadRecents()
            session = next
            onShown()
            next
        }

    private fun contextOf(services: AppServices, tunnel: RemoteConnection?) =
        ServerContext(
            services,
            tunnel,
            scope,
            preferences,
            grammars,
            agentCommand = settings::agentCommandLine,
            defaultAgent = settings::makeDefaultAgent,
            consent = { settings.telemetry.shared },
            onRemoteProjectOpened = remote::recordWorkspace,
        )

    // The preferences of every part of the IDE, the open project's last (on top of those last saved).
    private fun currentPreferences(): Map<String, String> =
        settings.toPreferences() + remote.toPreferences() + session.toPreferences()
}
