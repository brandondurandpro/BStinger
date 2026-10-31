package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Git through its command line; no repository or no git means no status. */
@Suppress("TooManyFunctions")
class CliGitClient : GitClient {
    private data class BlameVersion(val head: String?, val modified: Long, val size: Long)

    private class CachedBlame(val version: BlameVersion, val lines: List<BlameLine>)

    private val blames = ConcurrentHashMap<String, CachedBlame>()

    override suspend fun status(root: String): Map<String, GitFileStatus> =
        withContext(Dispatchers.IO) {
            gitOutput(root, "status", "--porcelain", "-uall")?.mapNotNull(::parse)?.toMap().orEmpty()
        }

    /** Reads `.git/HEAD` directly: much cheaper than spawning git. */
    override suspend fun branch(root: String): String? =
        withContext(Dispatchers.IO) {
            val ref = runCatching { File(root, ".git/HEAD").readLines().firstOrNull()?.trim() }.getOrNull()
            when {
                ref.isNullOrEmpty() -> null
                ref.startsWith(BRANCH_REF) -> ref.removePrefix(BRANCH_REF)
                else -> ref.take(SHORT_HASH_LENGTH)
            }
        }

    /** Differences with HEAD (with the index in a repository without commits); untracked files are all added. */
    override suspend fun diff(root: String, path: String): List<DiffLine> =
        withContext(Dispatchers.IO) {
            val tracked = gitOutput(root, "ls-files", "--error-unmatch", "--", path) != null
            if (tracked) {
                val output =
                    gitOutput(root, "diff", NO_COLOR, "--no-ext-diff", "-U3", "HEAD", "--", path)
                        ?: gitOutput(root, "diff", NO_COLOR, "--no-ext-diff", "-U3", "--cached", "--", path)
                UnifiedDiff().parse(output.orEmpty())
            } else {
                val lines = runCatching { File(root, path).readLines() }.getOrDefault(emptyList())
                UnifiedDiff.newFile(lines)
            }
        }

    /**
     * One `git for-each-ref` reads every local branch with its upstream and how far ahead or behind it is. Git reports
     * the distance from the last fetch: the popup fetches nothing, so that opening it never touches the network.
     */
    override suspend fun branchDetails(root: String): List<GitBranchInfo> =
        withContext(Dispatchers.IO) {
            gitOutput(root, FOR_EACH_REF, "--format=$BRANCH_FORMAT", "refs/heads")
                ?.mapNotNull(::parseBranch)
                .orEmpty()
        }

    override suspend fun branches(root: String): List<String> =
        branchDetails(root)
            .map { it.name }

    override suspend fun checkout(root: String, branch: String) {
        withContext(Dispatchers.IO) { runGit(root, "checkout", branch) }
    }

    /**
     * Deletes the branches in chunks (one Git process per chunk, within the command-line limit of Windows). A chunk
     * with a branch Git refuses (checked out in another worktree) is retried one by one, so the others still go; a
     * branch that another program already deleted counts as deleted, which makes a retry after a lost answer safe.
     */
    override suspend fun deleteBranches(root: String, branches: List<String>) {
        withContext(Dispatchers.IO) {
            require(branches.isNotEmpty()) { "No branch to delete" }
            val names = branches.filter { it.isNotBlank() }.distinct()
            val current = gitOutput(root, "symbolic-ref", "--short", "-q", "HEAD")?.firstOrNull()?.trim()
            require(current == null || current !in names) { "Cannot delete the current branch: $current" }
            val failures = mutableListOf<String>()
            names.chunked(DELETE_CHUNK_SIZE).forEach { chunk -> deleteChunk(root, chunk, failures) }
            if (failures.isNotEmpty()) throw IOException(failures.joinToString("; "))
        }
    }

    private fun deleteChunk(root: String, chunk: List<String>, failures: MutableList<String>) {
        val error = runCatching { runGit(root, "branch", "-D", "--", *chunk.toTypedArray()) }.exceptionOrNull()
        if (error == null) return
        chunk.forEach { name ->
            val failure = runCatching { runGit(root, "branch", "-D", "--", name) }.exceptionOrNull()
            if (failure != null && branchExists(root, name)) {
                failures += failure.message ?: "git branch: cannot delete $name"
            }
        }
    }

    private fun branchExists(root: String, name: String): Boolean =
        gitOutput(root, "show-ref", VERIFY, QUIET, "refs/heads/$name") != null

    /**
     * Commits exactly the changes of [paths] (the Git panel checks the files to commit), as they are on disk, and
     * leaves the changes of every other file for a later commit. `commit -- <paths>` never carries a change of
     * another file along, whatever the index holds; the `add` before it only brings the selected paths to Git, so
     * an untracked file is known to it when the commit accepts its pathspec.
     */
    override suspend fun commit(root: String, message: String, paths: List<String>) {
        require(message.isNotBlank()) { "A commit message is required" }
        require(paths.isNotEmpty()) { "No file to commit" }
        withContext(Dispatchers.IO) {
            if (paths.size > COMMIT_CHUNK_SIZE) {
                // Too many files for one command line (Windows bounds it): stage them chunk by chunk, then commit.
                paths.chunked(COMMIT_CHUNK_SIZE).forEach { chunk ->
                    runGit(root, "add", "-A", "--", *chunk.toTypedArray())
                }
                runGit(root, "commit", "-m", message)
            } else {
                runGit(root, "add", "-A", "--", *paths.toTypedArray())
                // The commit also takes the old path of a selected rename, so Git records a rename and not the old
                // file kept plus a new one; `add` uses the user's paths only: a rename staged ahead owns none.
                runGit(root, "commit", "-m", message, "--", *selectedPaths(root, paths).toTypedArray())
            }
        }
    }

    // The paths to give to Git: the user selects a rename by its new path, and its old path goes with it, or Git
    // would record the rename as the old file kept plus a new file. Copied paths (`C`, rare) are expanded alike.
    private fun selectedPaths(root: String, paths: List<String>): List<String> {
        val selected = LinkedHashSet(paths)
        val statuses = gitOutput(root, "status", "--porcelain", "-uall").orEmpty()
        statuses.forEach { line ->
            val code = line.take(2)
            if ('R' in code || 'C' in code) {
                val (old, newPath) = line.substring(PATH_START).split(" -> ")
                if (newPath.trim('"') in selected) selected += old.trim('"')
            }
        }
        return selected.toList()
    }

    override suspend fun pull(root: String): String =
        withContext(Dispatchers.IO) { runGit(root, NETWORK_TIMEOUT_SECONDS, "pull") }

    override suspend fun push(root: String): String =
        withContext(Dispatchers.IO) { runGit(root, NETWORK_TIMEOUT_SECONDS, "push") }

    // `--porcelain` would hide the summary the IDE shows; Git prints it on stderr, so the output is often empty.
    override suspend fun pushBranch(root: String, branch: String): String =
        withContext(Dispatchers.IO) {
            requireLocalBranch(root, branch)
            val upstream = upstreamOf(root, branch)
            if (upstream == null) {
                val refspec = "refs/heads/$branch:refs/heads/$branch"
                runGit(root, NETWORK_TIMEOUT_SECONDS, "push", "-u", DEFAULT_REMOTE, refspec)
            } else {
                runGit(root, NETWORK_TIMEOUT_SECONDS, "push", upstream.remote, "refs/heads/$branch:${upstream.ref}")
            }
        }

    // Fetching into the branch updates it only by fast-forward (no `+`), and Git refuses to touch a branch that is
    // checked out, here or in another worktree: the current branch goes through a real pull.
    override suspend fun pullBranch(root: String, branch: String): String =
        withContext(Dispatchers.IO) {
            requireLocalBranch(root, branch)
            val current = gitOutput(root, "symbolic-ref", "--short", "-q", "HEAD")?.firstOrNull()?.trim()
            if (current == branch) {
                runGit(root, NETWORK_TIMEOUT_SECONDS, "pull", "--ff-only")
            } else {
                val upstream = upstreamOf(root, branch) ?: throw IOException("$branch tracks no remote branch")
                runGit(root, NETWORK_TIMEOUT_SECONDS, "fetch", upstream.remote, "${upstream.ref}:refs/heads/$branch")
            }
        }

    override suspend fun remoteBranches(root: String): List<String> =
        withContext(Dispatchers.IO) {
            gitOutput(root, FOR_EACH_REF, "--format=%(refname:short)%00%(symref)", "refs/remotes")
                .orEmpty()
                .map { it.split('\u0000') }
                // `origin/HEAD` is a symbolic ref to the remote's default branch, not a branch.
                .filter { it.size == 2 && it[1].isEmpty() && it[0].isNotBlank() }
                .map { it[0] }
                .sorted()
        }

    // One record per commit, its fields separated by NUL and the records by RS (0x1e): subjects can hold anything
    // but those. `--` keeps a ref named like a file from being read as a path.
    override suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit> =
        withContext(Dispatchers.IO) {
            require(limit in 1..MAX_LOG_PAGE) { "A page holds 1 to $MAX_LOG_PAGE commits" }
            require(!ref.startsWith("-")) { "Not a ref: $ref" }
            val output =
                runGit(
                    root,
                    "log",
                    "--format=%H%x00%P%x00%an%x00%at%x00%D%x00%s%x1e",
                    "--skip=${skip.coerceAtLeast(0)}",
                    "--max-count=$limit",
                    ref,
                    "--",
                )
            output.split(RECORD_SEPARATOR).mapNotNull(::parseCommit)
        }

    override suspend fun searchLog(root: String, ref: String, query: String, skip: Int, limit: Int): List<GitCommit> =
        withContext(Dispatchers.IO) {
            require(limit in 1..MAX_LOG_PAGE) { "A page holds 1 to $MAX_LOG_PAGE commits" }
            require(!ref.startsWith("-")) { "Not a ref: $ref" }
            searchCommits(root, ref, query, skip.coerceAtLeast(0), limit)
        }

    override suspend fun commitDetails(root: String, hash: String): GitCommitDetails =
        withContext(Dispatchers.IO) {
            require(hash.matches(HASH)) { "Not a commit hash: $hash" }
            val message = runGit(root, "log", "-1", "--format=%B", hash).trimEnd()
            // `-z`: paths as they are (no quoting of spaces or accents); `--root` lists the files of a first commit.
            val raw = runGit(root, "diff-tree", "--no-commit-id", "-r", "-M", "--root", "--name-status", "-z", hash)
            GitCommitDetails(hash, message, parseNameStatus(raw))
        }

    // diff-tree compares two trees: the first parent's and the commit's (a merge shows what it brought to its first
    // parent); a first commit, with `--root`, is compared with nothing. Both paths of a rename are given, so that
    // `-M` sees the rename and shows only the lines that changed.
    override suspend fun commitDiff(root: String, hash: String, path: String, oldPath: String?): List<DiffLine> =
        withContext(Dispatchers.IO) {
            require(hash.matches(HASH)) { "Not a commit hash: $hash" }
            val paths = listOfNotNull(oldPath, path).distinct()
            val parent = gitOutput(root, "rev-parse", VERIFY, QUIET, "$hash^1")?.firstOrNull()?.trim()
            val range = if (parent == null) listOf("--root", hash) else listOf(parent, hash)
            val options = listOf("-p", "-M", "--no-commit-id", NO_COLOR, "-U3")
            val args = listOf("diff-tree") + options + range + "--" + paths
            UnifiedDiff().parse(runGit(root, *args.toTypedArray()).lines())
        }

    private fun parseCommit(record: String): GitCommit? {
        val fields = record.trimStart('\n').split('\u0000')
        if (fields.size < COMMIT_FIELD_COUNT || fields[0].isBlank()) return null
        val refs =
            fields[COMMIT_REFS_FIELD]
                .split(", ")
                .map { it.removePrefix("HEAD -> ") }
                .filter { it.isNotBlank() && it != "HEAD" }
        return GitCommit(
            hash = fields[0],
            parents = fields[1].split(' ').filter(String::isNotBlank),
            author = fields[2],
            time = fields[COMMIT_TIME_FIELD].toLongOrNull() ?: 0,
            subject = fields.drop(COMMIT_SUBJECT_FIELD).joinToString("\u0000"),
            refs = refs,
        )
    }

    // `-z --name-status`: a status, then its path; a rename or copy (R100, C75) has the old path, then the new one.
    private fun parseNameStatus(raw: String): List<GitCommitFile> {
        val parts = raw.split('\u0000').filter { it.isNotEmpty() }
        val files = ArrayList<GitCommitFile>()
        var index = 0
        while (index < parts.size) {
            val code = parts[index]
            val renamed = code.startsWith("R") || code.startsWith("C")
            if (renamed && index + 2 <= parts.lastIndex) {
                files += GitCommitFile(parts[index + 2], GitFileStatus.Renamed, oldPath = parts[index + 1])
                index += RENAME_PARTS
            } else if (index + 1 <= parts.lastIndex) {
                files += GitCommitFile(parts[index + 1], commitFileStatus(code))
                index += 2
            } else {
                break
            }
        }
        return files
    }

    private fun commitFileStatus(code: String): GitFileStatus =
        when (code.first()) {
            'A' -> GitFileStatus.Added
            'D' -> GitFileStatus.Deleted
            else -> GitFileStatus.Modified
        }

    private fun requireLocalBranch(root: String, branch: String) {
        if (gitOutput(root, "show-ref", VERIFY, QUIET, "refs/heads/$branch") == null) {
            throw IOException("No local branch $branch")
        }
    }

    // Remote and remote ref (`refs/heads/main`) that [branch] tracks, or null when it tracks none.
    private fun upstreamOf(root: String, branch: String): Upstream? {
        val line =
            gitOutput(
                root,
                FOR_EACH_REF,
                "--format=%(upstream:remotename)%00%(upstream:remoteref)",
                "refs/heads/$branch",
            )?.firstOrNull()
        val (remote, ref) = line?.split('\u0000')?.takeIf { it.size == 2 } ?: return null
        return if (remote.isBlank() || ref.isBlank()) null else Upstream(remote, ref)
    }

    private data class Upstream(val remote: String, val ref: String)

    /**
     * Kept until HEAD or the file changes: blaming a large file takes git hundreds of milliseconds, and the editor
     * asks again every time the window regains focus.
     */
    override suspend fun blame(root: String, path: String): List<BlameLine> =
        withContext(Dispatchers.IO) {
            if (gitOutput(root, "ls-files", "--error-unmatch", "--", path) == null) {
                emptyList()
            } else {
                val file = File(root, path)
                val head = gitOutput(root, "rev-parse", "HEAD")?.firstOrNull()
                val version = BlameVersion(head, file.lastModified(), file.length())
                val cached = blames[file.absolutePath]?.takeIf { it.version == version }
                cached?.lines ?: freshBlame(root, path, file.absolutePath, version)
            }
        }

    // A failure gives no annotation and is not cached.
    private fun freshBlame(root: String, path: String, key: String, version: BlameVersion): List<BlameLine> {
        val lines = runCatching { parseBlame(runGit(root, "blame", "--line-porcelain", "--", path)) }.getOrNull()
        if (lines != null) {
            if (blames.size >= MAX_CACHED_BLAMES) blames.clear()
            blames[key] = CachedBlame(version, lines)
        }
        return lines.orEmpty()
    }

    // One `for-each-ref` line: name, HEAD marker, upstream and its track, separated by NULs.
    private fun parseBranch(line: String): GitBranchInfo? {
        val fields = line.split('\u0000')
        val name = fields[0].trim()
        val parsed = fields.size >= BRANCH_FIELD_COUNT && name.isNotEmpty()
        return if (parsed) {
            val track = fields[3].trim()
            GitBranchInfo(
                name = name,
                current = fields[1].trim() == HEAD_MARKER,
                upstream = fields[2].trim().ifEmpty { null },
                ahead = trackCount(track, AHEAD_TRACK),
                behind = trackCount(track, BEHIND_TRACK),
                upstreamGone = track == GONE_TRACK,
            )
        } else {
            null
        }
    }

    private fun trackCount(track: String, pattern: Regex): Int {
        val match = pattern.find(track)
        return match?.groupValues?.get(TRACK_COUNT_GROUP)?.toIntOrNull() ?: 0
    }

    // One `git status --porcelain` line: two status letters, a space, then the path (`old -> new` for renames).
    private fun parse(line: String): Pair<String, GitFileStatus>? {
        val code = line.take(2)
        val status =
            when {
                line.length <= PATH_START -> null
                code == "??" -> GitFileStatus.Untracked
                'R' in code -> GitFileStatus.Renamed
                'A' in code -> GitFileStatus.Added
                'D' in code -> GitFileStatus.Deleted
                'M' in code -> GitFileStatus.Modified
                else -> null
            }
        return status?.let { line.substring(PATH_START).substringAfter(" -> ").trim('"') to it }
    }

    private companion object {
        const val BRANCH_REF = "ref: refs/heads/"
        const val PATH_START = 3
        const val NETWORK_TIMEOUT_SECONDS = 180L
        const val MAX_CACHED_BLAMES = 32
        const val HEAD_MARKER = "*"
        const val GONE_TRACK = "[gone]"
        const val BRANCH_FIELD_COUNT = 4
        const val DELETE_CHUNK_SIZE = 50
        const val COMMIT_CHUNK_SIZE = 500
        const val DEFAULT_REMOTE = "origin"
        const val NO_COLOR = "--no-color"
        const val VERIFY = "--verify"
        const val QUIET = "--quiet"
        const val FOR_EACH_REF = "for-each-ref"
        const val MAX_LOG_PAGE = 1000
        const val RECORD_SEPARATOR = '\u001e'
        const val COMMIT_FIELD_COUNT = 6
        const val COMMIT_TIME_FIELD = 3
        const val COMMIT_REFS_FIELD = 4
        const val COMMIT_SUBJECT_FIELD = 5
        const val RENAME_PARTS = 3
        val HASH = Regex("[0-9a-f]{7,64}")
        const val TRACK_COUNT_GROUP = 1
        const val BRANCH_FORMAT = "%(refname:short)%00%(HEAD)%00%(upstream:short)%00%(upstream:track)"
        val AHEAD_TRACK = Regex("""ahead (\d+)""")
        val BEHIND_TRACK = Regex("""behind (\d+)""")
    }
}
