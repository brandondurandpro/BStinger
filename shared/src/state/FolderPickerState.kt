package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import java.io.IOException

private const val PATH_CHECK_DEBOUNCE_MS = 250L

/**
 * Browsing the folders of a remote server, like VS Code's "Open Folder": starts in the SSH user's home, lists the
 * subfolders of the current one, and lets the user go up, back home, type a path, or open the current folder.
 */
@Stable
@Suppress("TooManyFunctions")
class FolderPickerState(
    private val fileSystem: ProjectFileSystem,
    private val scope: CoroutineScope,
    startPath: String,
    private val sshHome: String?,
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

    /** True while the typed path is waiting for the debounce or being checked on the server. */
    var checking: Boolean by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    private var loadJob: Job? = null
    private var autoLoadJob: Job? = null
    private var generation = 0

    val canGoUp: Boolean
        get() = current?.let { parentOf(it.path) != it.path } ?: false

    val canChoose: Boolean
        get() = current != null && !loading && !checking && samePath(path)

    val canGoHome: Boolean
        get() = sshHome != null

    init {
        navigate(startPath)
    }

    /**
     * The path field changed. A complete path is checked after a short debounce, so typing in a long path does not
     * send one request per keystroke; Enter ([submitPath]) and a folder click are immediate.
     */
    fun editPath(value: String) {
        path = value
        autoLoadJob?.cancel()
        autoLoadJob = null
        val requested = value.trim()
        if (requested.isEmpty() || samePath(requested)) {
            cancelPending()
            return
        }
        // A relative fragment cannot be resolved; wait for the user to finish an absolute or ~ path.
        if (!requested.startsWith("/") && !requested.startsWith("~")) {
            cancelPending()
            return
        }
        cancelPending()
        error = null
        checking = true
        autoLoadJob =
            scope.launch {
                delay(PATH_CHECK_DEBOUNCE_MS)
                // Do not rewrite the field while the user is typing: a trailing slash or ~ must stay as typed.
                load(requested, syncPath = false)
            }
    }

    /** Resolves what is in the path field immediately. */
    fun submitPath() {
        navigate(path)
    }

    /** Opens [entry] (a subfolder) and lists its own subfolders. */
    fun enter(entry: FileEntry) {
        navigate(entry.path)
    }

    fun goUp() {
        current?.let { navigate(parentOf(it.path)) }
    }

    fun goHome() {
        navigate(sshHome ?: "/")
    }

    /** The folder to open, or null when nothing has loaded yet or a path is still being checked. */
    fun choose(): String? = if (canChoose) current?.path else null

    fun dispose() {
        generation++
        autoLoadJob?.cancel()
        autoLoadJob = null
        loadJob?.cancel()
        loadJob = null
    }

    private fun navigate(target: String) {
        autoLoadJob?.cancel()
        autoLoadJob = null
        error = null
        checking = true
        load(target, syncPath = true)
    }

    private fun cancelPending() {
        checking = false
        loading = false
        // Invalidate a load that is still running so its late answer cannot overwrite what is being typed.
        generation++
        loadJob?.cancel()
        loadJob = null
    }

    @Suppress("CognitiveComplexMethod") // one place handles success, not-found, IO failure and stale answers
    private fun load(target: String, syncPath: Boolean) {
        loadJob?.cancel()
        val request = ++generation
        val requested = expandHome(target.trim().ifEmpty { sshHome ?: "/" })
        loadJob =
            scope.launch {
                loading = true
                checking = true
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
                            if (syncPath) path = root.path
                            entries = children
                        }
                    }
                } catch (e: IOException) {
                    if (request == generation) error = "Cannot read $requested: ${e.message}"
                } finally {
                    if (request == generation) {
                        loading = false
                        checking = false
                    }
                }
            }
    }

    private fun samePath(value: String): Boolean = expandHome(value.trim()).trimEnd('/') == current?.path?.trimEnd('/')

    private fun expandHome(value: String): String =
        when {
            sshHome == null -> value
            value == "~" -> sshHome
            value.startsWith("~/") -> sshHome.trimEnd('/') + value.substring(1)
            else -> value
        }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substringBeforeLast('/', "").ifEmpty { "/" }
    }
}
