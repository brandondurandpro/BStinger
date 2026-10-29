package org.example.kotlineditor.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.kotlineditor.syntax.Declaration

/** A file or directory of the opened project. Paths are absolute and platform-specific (on the server). */
data class FileEntry(
    /** Absolute path on the server. */
    val path: String,
    /** Last path segment, shown in the UI. */
    val name: String,
    /** Whether this is a directory. */
    val isDirectory: Boolean,
)

/** Result of reading a file from disk. */
sealed interface FileContent {
    /** A text file, split into [lines] without terminators; [lineSeparator] is restored on save. */
    class Text(
        /** Lines of the file. */
        val lines: List<String>,
        /** Line separator used by the file. */
        val lineSeparator: String = "\n",
    ) : FileContent

    /** A file that is not text (contains NUL bytes). */
    data object Binary : FileContent

    /** A file too large to be opened, of [size] bytes. */
    data class TooLarge(
        /** Size in bytes. */
        val size: Long,
    ) : FileContent

    /** A file that could not be read. */
    data class Error(
        /** Why it could not be read. */
        val message: String,
    ) : FileContent
}

/** One line matching a Find in Files query. */
data class TextMatch(
    /** File containing the match. */
    val entry: FileEntry,
    /** Zero-based line of the match. */
    val line: Int,
    /** Zero-based column of the match. */
    val column: Int,
    /** Text of the matching line. */
    val lineText: String,
)

/** A declaration found in a project file (Go to Symbol, Go to Declaration). */
data class FileSymbol(
    /** File declaring the symbol. */
    val entry: FileEntry,
    /** The declaration itself. */
    val declaration: Declaration,
)

/** Files of the projects. Implemented by the server, used by the client through the network. */
interface ProjectFileSystem {
    /** Returns the entry for [path] if it is an existing directory, `null` otherwise. */
    suspend fun resolveDirectory(path: String): FileEntry?

    /** Children of [directory], directories first, then case-insensitive by name (like IntelliJ). */
    suspend fun list(directory: FileEntry): List<FileEntry>

    /** Content of [file]. */
    suspend fun read(file: FileEntry): FileContent

    /** The regular file at [path], or `null` if there is none (used to restore sessions). */
    suspend fun fileAt(path: String): FileEntry?

    /** Replaces the content of [file] with [text]. */
    suspend fun write(file: FileEntry, text: String)

    /** Creates an empty file; [name] may contain `/` to create intermediate directories, like IntelliJ. */
    suspend fun createFile(directory: FileEntry, name: String): FileEntry

    /** Creates the directory [name] in [directory]. */
    suspend fun createDirectory(directory: FileEntry, name: String): FileEntry

    /** Renames [entry] to [newName] in the same directory. */
    suspend fun rename(entry: FileEntry, newName: String): FileEntry

    /** Deletes a file, or a directory with everything it contains. */
    suspend fun delete(entry: FileEntry)
}

/** Project-wide queries, run by the server next to the files so only results cross the network. */
interface ProjectSearch {
    /** Every regular file under [root], skipping VCS and build output directories. */
    suspend fun walkFiles(root: FileEntry, limit: Int = DEFAULT_WALK_LIMIT): List<FileEntry>

    /** Full-text search of [query] in the text files under [root]. */
    suspend fun findInFiles(root: FileEntry, query: String, ignoreCase: Boolean, limit: Int): List<TextMatch>

    /** Declarations of the Kotlin and Java sources under [root]. */
    suspend fun symbols(root: FileEntry): List<FileSymbol>

    /** Defaults of the search parameters. */
    companion object {
        /** Maximum number of files listed by [walkFiles] by default. */
        const val DEFAULT_WALK_LIMIT = 200_000
    }
}

/** Directories that are never indexed nor searched (IntelliJ excludes them too). */
val IGNORED_DIRECTORIES =
    setOf(".git", ".hg", ".svn", ".idea", ".gradle", ".kotlin", "build", "out", "node_modules", "target")

/** Recently opened projects, most recent first. */
interface RecentProjectsStore {
    /** The stored paths. */
    suspend fun load(): List<String>

    /** Replaces the stored paths. */
    suspend fun save(paths: List<String>)
}

/** [RecentProjectsStore] kept in memory. */
class InMemoryRecentProjectsStore(initial: List<String> = emptyList()) : RecentProjectsStore {
    /** The stored paths. */
    var paths: List<String> = initial
        private set

    override suspend fun load(): List<String> = paths

    override suspend fun save(paths: List<String>) {
        this.paths = paths
    }
}

/** Lets the user choose a project directory. */
fun interface DirectoryPicker {
    /** Asks the user for a project directory; `null` when cancelled. */
    suspend fun pickDirectory(): String?
}

/** A running shell: lines it prints, and lines sent to its standard input. */
interface ShellProcess {
    /** Output lines (stdout and stderr merged); completes when the shell exits. */
    val output: Flow<String>

    /** Sends one command line. */
    fun send(line: String)

    /** Stops the shell and its children. */
    fun destroy()
}

/** Starts shells. */
fun interface ShellLauncher {
    /** Starts a shell whose working directory is [directory]. */
    fun start(directory: String): ShellProcess
}

/** What is restored when a project is reopened: open files with their caret, and the active one. */
data class ProjectSession(
    /** Open files, in tab order. */
    val openFiles: List<SessionFile>,
    /** Path of the active file. */
    val activeFile: String?,
)

/** One open file of a [ProjectSession]. */
data class SessionFile(
    /** Path of the file. */
    val path: String,
    /** Caret line. */
    val line: Int,
    /** Caret column. */
    val column: Int,
)

/** Sessions of the projects, keyed by project path. */
interface SessionStore {
    /** The session of [projectPath], if any. */
    suspend fun load(projectPath: String): ProjectSession?

    /** Stores the session of [projectPath]. */
    suspend fun save(projectPath: String, session: ProjectSession)
}

/** [SessionStore] kept in memory. */
class InMemorySessionStore : SessionStore {
    /** The stored sessions. */
    val sessions = HashMap<String, ProjectSession>()

    override suspend fun load(projectPath: String) = sessions[projectPath]

    override suspend fun save(projectPath: String, session: ProjectSession) {
        sessions[projectPath] = session
    }
}

/** Git status of a changed file. */
enum class GitFileStatus {
    /** Changed since the last commit. */
    Modified,

    /** New and staged. */
    Added,

    /** New and not tracked. */
    Untracked,

    /** Deleted. */
    Deleted,

    /** Renamed or moved. */
    Renamed,
}

/** Kind of a line of a unified diff. */
enum class DiffKind {
    /** `@@ -a,b +c,d @@` header starting a block of changes. */
    Hunk,

    /** Unchanged line shown for context. */
    Context,

    /** Line only in the new version. */
    Added,

    /** Line only in the old version. */
    Removed,
}

/** One line of the differences of a file between HEAD and the working tree. */
data class DiffLine(
    /** What the line is. */
    val kind: DiffKind,
    /** Line number in the old version (1-based), -1 when it has none. */
    val oldLine: Int,
    /** Line number in the new version (1-based), -1 when it has none. */
    val newLine: Int,
    /** Text of the line, without the diff marker. */
    val text: String,
)

/** Git information about a project. */
interface GitClient {
    /** Status of changed files, keyed by path relative to [root] with `/` separators; empty when not a repository. */
    suspend fun status(root: String): Map<String, GitFileStatus>

    /** Current branch, or short commit hash when detached; `null` when [root] is not a repository. */
    suspend fun branch(root: String): String?

    /** Differences of [path] (relative to [root]) between HEAD and the working tree; empty when unchanged. */
    suspend fun diff(root: String, path: String): List<DiffLine>

    /** No Git support: no status, no branch, no differences. */
    object None : GitClient {
        override suspend fun status(root: String) = emptyMap<String, GitFileStatus>()

        override suspend fun branch(root: String): String? = null

        override suspend fun diff(root: String, path: String): List<DiffLine> = emptyList()
    }
}

/** State of the link with the server, shown in the status bar. */
enum class ConnectionStatus {
    /** Requests go through. */
    Connected,

    /** The link was lost; requests wait and are retried once it is back. */
    Reconnecting,

    /** The client was closed or rejected by the server. */
    Disconnected,
}

/** Every service the IDE uses. */
class AppServices(
    /** Files. */
    val fileSystem: ProjectFileSystem,
    /** Project-wide queries. */
    val search: ProjectSearch,
    /** Recently opened projects. */
    val recentProjects: RecentProjectsStore,
    /** Choice of a project directory. */
    val directoryPicker: DirectoryPicker,
    /** Terminal shells. */
    val shell: ShellLauncher,
    /** Open files of each project. */
    val sessions: SessionStore = InMemorySessionStore(),
    /** Git information. */
    val git: GitClient = GitClient.None,
    /** State of the link with the server. */
    val connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
)

/** Last segment of a path, whatever the separator. */
fun pathName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val name = trimmed.substring(trimmed.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
    return name.ifEmpty { path }
}
