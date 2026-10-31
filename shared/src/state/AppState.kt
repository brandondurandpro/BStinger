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

    /** The project being opened (its directory resolved on the server), shown until it opens or fails. */
    var opening: String? by mutableStateOf(null)
        private set

    // Only the latest request opens its project: a late answer must not replace a project chosen after it.
    private var openRequest = 0

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

    /** The recent projects other than the open one, most recent first: those the project menu switches to. */
    val otherProjects: List<String>
        get() {
            val current = (screen as? Screen.Project)?.project?.root?.path
            return recentProjects.filter { it != current }
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

    fun openProject(path: String) {
        val request = ++openRequest
        error = null
        opening = path
        scope.launch {
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
            if (request == openRequest) {
                opening = null
                root?.let { open(it) }
            }
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
        openRequest++
        opening = null
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

    // The open project's settings on top of the last known preferences.
    private fun currentPreferences(): Map<String, String> {
        val project = (screen as? Screen.Project)?.project ?: return preferences + fontSizePreference()
        val autoSelect = PreferenceKeys.AUTO_SELECT_OPENED_FILE to project.autoSelectOpenedFile.toString()
        return preferences + project.toolWindows.toPreferences() + autoSelect + fontSizePreference()
    }

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
