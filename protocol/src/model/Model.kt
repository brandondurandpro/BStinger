package org.example.kotlineditor.model

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

/** Kind of change of a line compared to HEAD, for the gutter marks of the editor. */
enum class GitChangeKind {
    /** A line that does not exist in HEAD. */
    Added,

    /** A line changed since HEAD. */
    Modified,

    /** Lines removed since HEAD, anchored before the given line. */
    Deleted,
}

/** One gutter change mark of an open file. */
data class GitLineChange(
    /** Zero-based line number; for [GitChangeKind.Deleted], the removal happened before it. */
    val line: Int,
    /** What changed on that line. */
    val kind: GitChangeKind,
)

/** One line annotated by `git blame`. */
data class BlameLine(
    /** Zero-based line number. */
    val line: Int,
    /** Short commit hash. */
    val commit: String,
    /** Author name. */
    val author: String,
    /** Author date, `yyyy-MM-dd`. */
    val date: String,
)

/** A commit of the history of a branch (the Branches tool window). */
data class GitCommit(
    /** Full hash. */
    val hash: String,
    /** Full hashes of its parents: several for a merge, none for a root commit. */
    val parents: List<String>,
    /** Author name. */
    val author: String,
    /** Author date, in seconds since the epoch. */
    val time: Long,
    /** First line of the message. */
    val subject: String,
    /** Branches and tags pointing at it (`main`, `origin/main`, `tag: v1`), `HEAD` excluded. */
    val refs: List<String> = emptyList(),
) {
    /** Abbreviated hash shown in lists. */
    val shortHash: String get() = hash.take(SHORT_HASH)

    private companion object {
        const val SHORT_HASH = 8
    }
}

/** A file changed by a commit, compared with its first parent. */
data class GitCommitFile(
    /** Path relative to the root, with `/` separators. */
    val path: String,
    /** How the commit changed it. */
    val status: GitFileStatus,
    /** Former path of a renamed file. */
    val oldPath: String? = null,
)

/** What a commit changed: its complete message and its files. */
data class GitCommitDetails(
    /** Full hash of the commit. */
    val hash: String,
    /** Complete message, subject and body. */
    val message: String,
    /** Files changed, compared with the first parent. */
    val files: List<GitCommitFile>,
)

/** A local branch and its synchronization with the remote-tracking branch it follows. */
data class GitBranchInfo(
    /** Short name of the branch, as shown by `git branch` (`main`, `feature/login`). */
    val name: String,
    /** Whether this is the checked-out branch. */
    val current: Boolean,
    /** Remote-tracking branch it follows (`origin/main`), or `null` when it tracks nothing. */
    val upstream: String?,
    /** Commits of the branch that its upstream does not have. */
    val ahead: Int,
    /** Commits of the upstream that the branch does not have: how far behind the remote it is. */
    val behind: Int,
    /** Whether the upstream branch was deleted on the remote (Git reports it as `gone`). */
    val upstreamGone: Boolean = false,
) {
    /** Whether this branch follows an upstream that still exists. */
    val hasUpstream: Boolean get() = upstream != null && !upstreamGone
}

/** Worktrees of a repository: the branches the agent works on, each checked out in a directory of its own. */
interface WorktreeClient {
    /**
     * Path of a worktree of the repository of [root] with [branch] checked out: the existing one, or a new one kept
     * in the Git directory of the repository (the branch is created from HEAD when it does not exist).
     */
    suspend fun worktree(root: String, branch: String): String

    /** Removes the worktree at [path]; fails, keeping it, when it has uncommitted changes. */
    suspend fun removeWorktree(root: String, path: String)
}

/** Git information about a project. */
@Suppress("ComplexInterface", "TooManyFunctions") // one function per Git operation the IDE offers
interface GitClient : WorktreeClient {
    /** Status of changed files, keyed by path relative to [root] with `/` separators; empty when not a repository. */
    suspend fun status(root: String): Map<String, GitFileStatus>

    /** Current branch, or short commit hash when detached; `null` when [root] is not a repository. */
    suspend fun branch(root: String): String?

    /** Differences of [path] (relative to [root]) between HEAD and the working tree; empty when unchanged. */
    suspend fun diff(root: String, path: String): List<DiffLine>

    /** Local branches of the repository, with their synchronization against their remote-tracking branch. */
    suspend fun branchDetails(root: String): List<GitBranchInfo>

    /** Local branches of the repository. */
    suspend fun branches(root: String): List<String>

    /** Checks out the local branch [branch]. */
    suspend fun checkout(root: String, branch: String)

    /**
     * Deletes the local branches [branches], even when they are not merged. The current branch is refused, and a
     * branch that another program already deleted is not an error: deleting is retried safely after a lost answer.
     */
    suspend fun deleteBranches(root: String, branches: List<String>)

    /**
     * Commits the changes of [paths] (relative to [root]) with [message], leaving the changes of every other file
     * for a later commit. A rename is listed by its new path, and the commit keeps its old path with it so Git
     * records a rename; [paths] is never empty (the client refuses to commit without a selection).
     */
    suspend fun commit(root: String, message: String, paths: List<String>)

    /** Pulls the current branch; returns what Git printed. */
    suspend fun pull(root: String): String

    /** Pushes the current branch; returns what Git printed. */
    suspend fun push(root: String): String

    /**
     * Pushes [branch] to the remote branch it tracks, or to a new branch of the same name on `origin` (which it then
     * tracks) when it tracks none. Works for any local branch, without checking it out; returns what Git printed.
     */
    suspend fun pushBranch(root: String, branch: String): String

    /**
     * Brings [branch] up to date with the remote branch it tracks without checking it out: a fast-forward only,
     * refused when the branches diverged. The current branch is pulled like [pull]. Returns what Git printed.
     */
    suspend fun pullBranch(root: String, branch: String): String

    /** Annotations (author and date) of each line of [path], empty when the file has no history. */
    suspend fun blame(root: String, path: String): List<BlameLine>

    /** Remote-tracking branches (`origin/main`), without the remotes' `HEAD`. */
    suspend fun remoteBranches(root: String): List<String>

    /** Commits reachable from [ref], newest first: [limit] of them after skipping [skip]; empty past the end. */
    suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit>

    /**
     * Commits reachable from [ref] whose message or author contains [query] (ignoring case), or whose hash starts
     * with it, newest first: [limit] of them after skipping the first [skip] matches; empty past the last one.
     */
    suspend fun searchLog(root: String, ref: String, query: String, skip: Int, limit: Int): List<GitCommit>

    /** The complete message and the changed files of commit [hash]. */
    suspend fun commitDetails(root: String, hash: String): GitCommitDetails

    /**
     * What commit [hash] changed in [path], compared with its first parent (with nothing for a first commit);
     * [oldPath] is the former path of a renamed file.
     */
    suspend fun commitDiff(root: String, hash: String, path: String, oldPath: String? = null): List<DiffLine>

    /** No Git support: no status, no branch, no differences. */
    @Suppress("TooManyFunctions")
    object None : GitClient {
        override suspend fun status(root: String) = emptyMap<String, GitFileStatus>()

        override suspend fun branch(root: String): String? = null

        override suspend fun diff(root: String, path: String): List<DiffLine> = emptyList()

        override suspend fun branchDetails(root: String): List<GitBranchInfo> = emptyList()

        override suspend fun branches(root: String): List<String> = emptyList()

        override suspend fun checkout(root: String, branch: String) = Unit

        override suspend fun deleteBranches(root: String, branches: List<String>) = Unit

        override suspend fun commit(root: String, message: String, paths: List<String>) = Unit

        override suspend fun pull(root: String): String = ""

        override suspend fun push(root: String): String = ""

        override suspend fun pushBranch(root: String, branch: String): String = ""

        override suspend fun pullBranch(root: String, branch: String): String = ""

        override suspend fun blame(root: String, path: String): List<BlameLine> = emptyList()

        override suspend fun remoteBranches(root: String): List<String> = emptyList()

        override suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit> = emptyList()

        override suspend fun searchLog(root: String, ref: String, query: String, skip: Int, limit: Int) =
            emptyList<GitCommit>()

        override suspend fun commitDetails(root: String, hash: String) = GitCommitDetails(hash, "", emptyList())

        override suspend fun commitDiff(root: String, hash: String, path: String, oldPath: String?) =
            emptyList<DiffLine>()

        override suspend fun worktree(root: String, branch: String): String =
            throw UnsupportedOperationException("Git is not available")

        override suspend fun removeWorktree(root: String, path: String) = Unit
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
    val terminal: TerminalLauncher,
    /** Open files of each project. */
    val sessions: SessionStore = InMemorySessionStore(),
    /** Git information. */
    val git: GitClient = GitClient.None,
    /** State of the link with the server. */
    val connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
    /** Capture of the frames exchanged with the server (devtools), when there is a server. */
    val network: NetworkInspector? = null,
)

/** Last segment of a path, whatever the separator. */
fun pathName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val name = trimmed.substring(trimmed.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
    return name.ifEmpty { path }
}
