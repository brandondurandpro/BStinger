package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry
import java.io.IOException

/** The whole IDE: the welcome screen or the open project, plus IDE-wide settings. */
@Stable
class AppState(private val services: AppServices, private val scope: CoroutineScope) {
    var screen: Screen by mutableStateOf(Screen.Welcome)
        private set

    val recentProjects = mutableStateListOf<String>()

    /** The link with the server (reconnecting after a network failure, ...). */
    val connection get() = services.connection

    var error: String? by mutableStateOf(null)
        private set

    /** Whether the devtools were opened (Ctrl+Alt+Shift+D) since the IDE started: shows their buttons. */
    var devtools by mutableStateOf(false)

    /** Editor font size in points, shared by every editor (Alt+Shift+. / Alt+Shift+, / Ctrl+wheel). */
    var editorFontSize by mutableFloatStateOf(DEFAULT_EDITOR_FONT_SIZE)
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
        scope.launch { runCatching { services.recentProjects.load() }.onSuccess { recentProjects.addAll(it) } }
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

    fun browseForProject() {
        scope.launch { services.directoryPicker.pickDirectory()?.let(::openProject) }
    }

    fun removeRecent(path: String) {
        recentProjects.remove(path)
        saveRecent()
    }

    fun closeProject() {
        (screen as? Screen.Project)?.project?.let { close(it) }
        screen = Screen.Welcome
    }

    /** Saves everything, then calls [onDone] (used when the window is closed). */
    fun exit(onDone: () -> Unit) {
        scope.launch {
            (screen as? Screen.Project)?.project?.let {
                runCatching { services.sessions.save(it.root.path, it.session()) }
                runCatching { it.editor.flush() }
            }
            onDone()
        }
    }

    private suspend fun open(root: FileEntry) {
        error = null
        recentProjects.remove(root.path)
        recentProjects.add(0, root.path)
        while (recentProjects.size > MAX_RECENT) recentProjects.removeAt(recentProjects.lastIndex)
        saveRecent()
        (screen as? Screen.Project)?.project?.let { close(it) }
        val project = ProjectState(root, services, scope)
        runCatching { services.sessions.load(root.path) }.getOrNull()?.let(project::restoreSession)
        screen = Screen.Project(project)
    }

    private fun saveRecent() {
        val recent = recentProjects.toList()
        scope.launch { runCatching { services.recentProjects.save(recent) } }
    }

    private fun close(project: ProjectState) {
        val session = project.session()
        scope.launch {
            runCatching { services.sessions.save(project.root.path, session) }
            runCatching { project.editor.flush() }
            project.dispose()
        }
    }

    companion object {
        private const val MAX_RECENT = 20
        const val DEFAULT_EDITOR_FONT_SIZE = 13f
        const val MIN_EDITOR_FONT_SIZE = 8f
        const val MAX_EDITOR_FONT_SIZE = 40f
    }
}
