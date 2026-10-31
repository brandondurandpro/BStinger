package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import java.io.File
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
                    gitOutput(root, "diff", "--no-color", "--no-ext-diff", "-U3", "HEAD", "--", path)
                        ?: gitOutput(root, "diff", "--no-color", "--no-ext-diff", "-U3", "--cached", "--", path)
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
            gitOutput(root, "for-each-ref", "--format=$BRANCH_FORMAT", "refs/heads")
                ?.mapNotNull(::parseBranch)
                .orEmpty()
        }

    override suspend fun branches(root: String): List<String> =
        branchDetails(root)
            .map { it.name }

    override suspend fun checkout(root: String, branch: String) {
        withContext(Dispatchers.IO) { runGit(root, "checkout", branch) }
    }

    override suspend fun commit(root: String, message: String) {
        require(message.isNotBlank()) { "A commit message is required" }
        withContext(Dispatchers.IO) {
            runGit(root, "add", "-A")
            runGit(root, "commit", "-m", message)
        }
    }

    override suspend fun pull(root: String): String =
        withContext(Dispatchers.IO) { runGit(root, NETWORK_TIMEOUT_SECONDS, "pull") }

    override suspend fun push(root: String): String =
        withContext(Dispatchers.IO) { runGit(root, NETWORK_TIMEOUT_SECONDS, "push") }

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
        const val TRACK_COUNT_GROUP = 1
        const val BRANCH_FORMAT = "%(refname:short)%00%(HEAD)%00%(upstream:short)%00%(upstream:track)"
        val AHEAD_TRACK = Regex("""ahead (\d+)""")
        val BEHIND_TRACK = Regex("""behind (\d+)""")
    }
}
