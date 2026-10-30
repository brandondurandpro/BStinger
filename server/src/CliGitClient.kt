package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import java.io.File

/** Git through its command line; no repository or no git means no status. */
class CliGitClient : GitClient {
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

    override suspend fun branches(root: String): List<String> =
        withContext(Dispatchers.IO) {
            runGit(root, "branch", "--format=%(refname:short)")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }

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

    override suspend fun blame(root: String, path: String): List<BlameLine> =
        withContext(Dispatchers.IO) {
            if (gitOutput(root, "ls-files", "--error-unmatch", "--", path) == null) {
                emptyList()
            } else {
                runCatching { runGit(root, "blame", "--line-porcelain", "--", path) }
                    .map(::parseBlame)
                    .getOrDefault(emptyList())
            }
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
    }
}
