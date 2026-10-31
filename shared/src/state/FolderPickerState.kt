package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import java.io.IOException

/**
 * Browsing the folders of a remote server, like VS Code's "Open Folder": starts in the SSH user's home, lists the
 * subfolders of the current one, and lets the user go up, back home, type a path, or open the current folder.
 */
@Stable
class FolderPickerState(
    private val fileSystem: ProjectFileSystem,
    private val scope: CoroutineScope,
    startPath: String,
    /** Home directory of the SSH user, used for `~` and for the Home button. */
    private val home: String?,
) {
    /** The folder currently shown, once it has been loaded. */
    var current: FileEntry? by mutableStateOf(null)
        private set

    /** Its subfolders, sorted by name. */
    var entries: List<FileEntry> by mutableStateOf(emptyList())
        private set

    /** Text of the path field: the current folder, or what the user is typing. */
    var path: String by mutableStateOf(startPath)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    private var loadJob: Job? = null
    private var generation = 0

    val canGoUp: Boolean
        get() = current?.let { parentOf(it.path) != it.path } ?: false

    val canChoose: Boolean
        get() = current != null && !loading

    val canGoHome: Boolean
        get() = home != null

    init {
        load(startPath)
    }

    /** The path field changed; it is only resolved on [submitPath] or Enter. */
    fun editPath(value: String) {
        path = value
    }

    /** Resolves what is in the path field. */
    fun submitPath() {
        load(path)
    }

    /** Opens [entry] (a subfolder) and lists its own subfolders. */
    fun enter(entry: FileEntry) {
        load(entry.path)
    }

    fun goUp() {
        current?.let { load(parentOf(it.path)) }
    }

    fun goHome() {
        load(home ?: "/")
    }

    /** The folder to open, or null when nothing has loaded yet. */
    fun choose(): String? = if (canChoose) current?.path else null

    fun dispose() {
        generation++
        loadJob?.cancel()
        loadJob = null
    }

    private fun load(target: String) {
        loadJob?.cancel()
        val request = ++generation
        val requested = expandHome(target.trim().ifEmpty { home ?: "/" })
        loadJob =
            scope.launch {
                loading = true
                error = null
                try {
                    val root = fileSystem.resolveDirectory(requested)
                    if (root == null || !root.isDirectory) {
                        error = "Directory not found: $requested"
                    } else {
                        val children =
                            fileSystem
                                .list(root)
                                .filter { it.isDirectory }
                                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        if (request == generation) {
                            current = root
                            path = root.path
                            entries = children
                        }
                    }
                } catch (e: IOException) {
                    if (request == generation) error = "Cannot read $requested: ${e.message}"
                } finally {
                    if (request == generation) loading = false
                }
            }
    }

    private fun expandHome(value: String): String =
        when {
            home == null -> value
            value == "~" -> home
            value.startsWith("~/") -> home.trimEnd('/') + value.substring(1)
            else -> value
        }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substringBeforeLast('/', "").ifEmpty { "/" }
    }
}
