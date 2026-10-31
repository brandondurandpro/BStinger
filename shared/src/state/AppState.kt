package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry
import java.io.IOException

/** How editors draw their lines: on one canvas, or as one composed row per line (being replaced). */
enum class EditorRenderer { Canvas, Rows }

/** The whole IDE: the welcome screen or the open project, plus IDE-wide settings. */
@Stable
@Suppress("TooManyFunctions")
class AppState(
    private val services: AppServices,
    private val scope: CoroutineScope,
    private val preferenceStore: PreferenceStore = InMemoryPreferenceStore(),
) {
    // Last preferences known: those of the open project are read live, these cover the welcome screen.
    private var preferences: Map<String, String> = preferenceStore.load()

    var screen: Screen by mutableStateOf(Screen.Welcome)
        private set

    val recentProjects = mutableStateListOf<String>()

    /** The link with the server (reconnecting after a network failure, ...). */
    val connection get() = services.connection

    var error: String? by mutableStateOf(null)
        private set

    /** Whether the devtools were opened (Ctrl+Alt+Shift+D) since the IDE started: shows their buttons. */
    var devtools by mutableStateOf(false)

    /** How editors draw their lines; Ctrl+Alt+Shift+R switches, while the canvas renderer replaces the rows. */
    var editorRenderer by mutableStateOf(savedRenderer())
        private set

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
        scope.launch { savePreferencesOnChange() }
        scope.launch { runCatching { services.recentProjects.load() }.onSuccess { recentProjects.addAll(it) } }
    }

    fun zoomEditor(delta: Float) {
        editorFontSize = (editorFontSize + delta).coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
    }

    fun resetEditorZoom() {
        editorFontSize = DEFAULT_EDITOR_FONT_SIZE
    }

    fun toggleEditorRenderer() {
        editorRenderer = if (editorRenderer == EditorRenderer.Canvas) EditorRenderer.Rows else EditorRenderer.Canvas
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
            runCatching { preferenceStore.save(currentPreferences()) }
            onDone()
        }
    }

    private fun savedFontSize(): Float =
        preferences[PreferenceKeys.EDITOR_FONT_SIZE]
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
            ?: DEFAULT_EDITOR_FONT_SIZE

    private fun savedRenderer(): EditorRenderer =
        EditorRenderer.entries.find { it.name == preferences[PreferenceKeys.EDITOR_RENDERER] } ?: EditorRenderer.Canvas

    // The open project's settings on top of the last known preferences.
    private fun currentPreferences(): Map<String, String> {
        val project = (screen as? Screen.Project)?.project ?: return preferences + editorPreferences()
        val autoSelect = PreferenceKeys.AUTO_SELECT_OPENED_FILE to project.autoSelectOpenedFile.toString()
        return preferences + project.toolWindows.toPreferences() + autoSelect + editorPreferences()
    }

    private fun editorPreferences() =
        mapOf(
            PreferenceKeys.EDITOR_FONT_SIZE to editorFontSize.toString(),
            PreferenceKeys.EDITOR_RENDERER to editorRenderer.name,
        )

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
        (screen as? Screen.Project)?.project?.let { close(it) }
        val project = ProjectState(root, services, scope, preferences)
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
