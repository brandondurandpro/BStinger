package org.example.kotlineditor.model

/** The state Git reports for a file of the project. */
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

    /** Both sides of a merge changed it: the file waits for the user to resolve it (a three-way merge). */
    Conflicted,
}

/** The commits that changed a conflicted file on each side since the two sides diverged, newest first. */
data class MergeHistory(
    /** Commits of the current branch. */
    val ours: List<GitCommit>,
    /** Commits of the branch being merged in (or of the commit being applied). */
    val theirs: List<GitCommit>,
)

/**
 * The three versions of a conflicted file, as the index keeps them during a merge, rebase or cherry-pick. A side is
 * `null` when that version has no such file: added by one side only (no [base]), or deleted by one side.
 */
data class MergeSides(
    /** The common ancestor of the two sides. */
    val base: FileContent.Text?,
    /** The version of the current branch (what HEAD holds). */
    val ours: FileContent.Text?,
    /** The version being merged in. */
    val theirs: FileContent.Text?,
)

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

/** The commits HEAD has that a base branch lacks ([ahead]), and those the base has that HEAD lacks ([behind]). */
data class AheadBehind(val ahead: Int, val behind: Int)

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
     * in the Git directory of the repository. A branch that does not exist is created from [base] (a local branch or a
     * remote-tracking one like `origin/main`, which it does not follow), from HEAD when there is none; an existing
     * branch keeps its commits, and so does an existing worktree.
     */
    suspend fun worktree(root: String, branch: String, base: String? = null): String

    /** Removes the worktree at [path]; fails, keeping it, when it has uncommitted changes. */
    suspend fun removeWorktree(root: String, path: String)
}

/** How `git reset` treats the index and the working tree. */
enum class GitResetMode {
    /** Moves the branch only: the changes stay staged. */
    Soft,

    /** Moves the branch and unstages the changes, keeping the files as they are. */
    Mixed,

    /** Moves the branch and throws away every uncommitted change. */
    Hard,
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

    /**
     * The lines of [path] (relative to [root]) as HEAD holds them, as the index holds them when there is no commit;
     * null when the file is not tracked. A final newline ends the last line, it does not start another one. The
     * editor compares them with what is typed, for its gutter marks.
     */
    suspend fun headLines(root: String, path: String): List<String>?

    /**
     * The three versions of the conflicted file [path] (relative to [root]); fails when the file is not in conflict or
     * when a version is not text.
     */
    suspend fun mergeSides(root: String, path: String): MergeSides

    /** The commits that changed [path] on each side of the merge in progress since the sides diverged. */
    suspend fun mergeHistory(root: String, path: String): MergeHistory

    /**
     * Resolves the conflict of [path] with [result]: writes it to the file, keeping its encoding and permissions, and
     * stages it, which ends the conflict for Git. Repeating the call after a lost answer writes the same text again.
     */
    suspend fun resolveConflict(root: String, path: String, result: FileContent.Text)

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

    /**
     * Discards the uncommitted changes of [paths] (relative to [root]): a tracked file goes back to what HEAD holds (a
     * renamed one by both its paths), a new file is deleted. A path without change is left alone, so that repeating
     * the call after a lost answer is safe. Changes nothing else: not HEAD, not the other files.
     */
    suspend fun revert(root: String, paths: List<String>)

    /** Pulls the current branch; returns what Git printed. */
    suspend fun pull(root: String): String

    /** Pushes the current branch; returns what Git printed. */
    suspend fun push(root: String): String

    /**
     * Fetches every remote, dropping the remote-tracking branches deleted there, so that [branchDetails] counts the
     * commits to pull against what the remotes have now. Changes no local branch and no file.
     */
    suspend fun fetch(root: String)

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

    /**
     * Creates the branch [name] at [startPoint] (a branch or a commit hash) and checks it out when [switchTo]. Fails
     * when [name] already exists, changing nothing.
     */
    suspend fun createBranch(root: String, name: String, startPoint: String, switchTo: Boolean)

    /** Renames the local branch [oldName] to [newName]; fails when [newName] exists. */
    suspend fun renameBranch(root: String, oldName: String, newName: String)

    /**
     * Checks out the remote-tracking branch [remoteBranch] (`origin/feature`) as a local branch of the same name
     * without the remote (`feature`) that tracks it, or just switches to it when it exists; returns its name.
     */
    suspend fun checkoutRemote(root: String, remoteBranch: String): String

    /** Checks out the commit [hash], leaving HEAD detached. */
    suspend fun checkoutCommit(root: String, hash: String)

    /** Creates the tag [name] on the commit [hash]; fails when it exists. */
    suspend fun createTag(root: String, name: String, hash: String)

    /**
     * Merges [branch] into the current branch. Returns what Git printed; when the merge stops on conflicts it returns
     * a message saying so and leaves the conflicted files for the merge editor, it does not fail.
     */
    suspend fun merge(root: String, branch: String): String

    /** Rebases the current branch onto [onto]; stops on conflicts like [merge]. */
    suspend fun rebase(root: String, onto: String): String

    /** Applies the changes of the commit [hash] on the current branch; refused for a merge commit. */
    suspend fun cherryPick(root: String, hash: String): String

    /** Commits the inverse of the commit [hash] on the current branch; refused for a merge commit. */
    suspend fun revertCommit(root: String, hash: String): String

    /** Moves the current branch to the commit [hash], keeping or discarding the changes as [mode] says. */
    suspend fun reset(root: String, hash: String, mode: GitResetMode): String

    /** Annotations (author and date) of each line of [path], empty when the file has no history. */
    suspend fun blame(root: String, path: String): List<BlameLine>

    /** Remote-tracking branches (`origin/main`), without the remotes' `HEAD`. */
    suspend fun remoteBranches(root: String): List<String>

    /** The default branch of the remote (`origin/main`, what its `HEAD` points to), null when Git does not know it. */
    suspend fun defaultRemoteBranch(root: String): String?

    /** Commits reachable from [ref], newest first: [limit] of them after skipping [skip]; empty past the end. */
    suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit>

    /** How far HEAD is from the branch [base] (local, or remote-tracking like `origin/main`): see [AheadBehind]. */
    suspend fun aheadBehind(root: String, base: String): AheadBehind

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

        override suspend fun mergeSides(root: String, path: String): MergeSides =
            throw UnsupportedOperationException("Git is not available")

        override suspend fun mergeHistory(root: String, path: String) = MergeHistory(emptyList(), emptyList())

        override suspend fun resolveConflict(root: String, path: String, result: FileContent.Text) = Unit

        override suspend fun branchDetails(root: String): List<GitBranchInfo> = emptyList()

        override suspend fun branches(root: String): List<String> = emptyList()

        override suspend fun checkout(root: String, branch: String) = Unit

        override suspend fun deleteBranches(root: String, branches: List<String>) = Unit

        override suspend fun commit(root: String, message: String, paths: List<String>) = Unit

        override suspend fun revert(root: String, paths: List<String>) = Unit

        override suspend fun pull(root: String): String = ""

        override suspend fun push(root: String): String = ""

        override suspend fun fetch(root: String) = Unit

        override suspend fun pushBranch(root: String, branch: String): String = ""

        override suspend fun pullBranch(root: String, branch: String): String = ""

        override suspend fun createBranch(root: String, name: String, startPoint: String, switchTo: Boolean) = Unit

        override suspend fun renameBranch(root: String, oldName: String, newName: String) = Unit

        override suspend fun checkoutRemote(root: String, remoteBranch: String): String = remoteBranch

        override suspend fun checkoutCommit(root: String, hash: String) = Unit

        override suspend fun createTag(root: String, name: String, hash: String) = Unit

        override suspend fun merge(root: String, branch: String): String = ""

        override suspend fun rebase(root: String, onto: String): String = ""

        override suspend fun cherryPick(root: String, hash: String): String = ""

        override suspend fun revertCommit(root: String, hash: String): String = ""

        override suspend fun reset(root: String, hash: String, mode: GitResetMode): String = ""

        override suspend fun headLines(root: String, path: String): List<String>? = null

        override suspend fun aheadBehind(root: String, base: String) = AheadBehind(0, 0)

        override suspend fun blame(root: String, path: String): List<BlameLine> = emptyList()

        override suspend fun remoteBranches(root: String): List<String> = emptyList()

        override suspend fun defaultRemoteBranch(root: String): String? = null

        override suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit> = emptyList()

        override suspend fun searchLog(root: String, ref: String, query: String, skip: Int, limit: Int) =
            emptyList<GitCommit>()

        override suspend fun commitDetails(root: String, hash: String) = GitCommitDetails(hash, "", emptyList())

        override suspend fun commitDiff(root: String, hash: String, path: String, oldPath: String?) =
            emptyList<DiffLine>()

        override suspend fun worktree(root: String, branch: String, base: String?): String =
            throw UnsupportedOperationException("Git is not available")

        override suspend fun removeWorktree(root: String, path: String) = Unit
    }
}
