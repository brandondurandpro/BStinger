package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import java.io.File

/** Git through its command line; no repository or no git means no status. */
class CliGitClient : GitClient {
    override suspend fun status(root: String): Map<String, GitFileStatus> =
        withContext(Dispatchers.IO) {
            git(root, "status", "--porcelain", "-uall")?.mapNotNull(::parse)?.toMap().orEmpty()
        }

    /** Reads `.git/HEAD` directly: much cheaper than spawning git. */
    override suspend fun branch(root: String): String? =
        withContext(Dispatchers.IO) {
            val ref = runCatching { File(root, ".git/HEAD").readLines().firstOrNull()?.trim() }.getOrNull()
            when {
                ref.isNullOrEmpty() -> null
                ref.startsWith(BRANCH_REF) -> ref.removePrefix(BRANCH_REF)
                else -> ref.take(SHORT_HASH)
            }
        }

    /** Differences with HEAD (with the index in a repository without commits); untracked files are all added. */
    override suspend fun diff(root: String, path: String): List<DiffLine> =
        withContext(Dispatchers.IO) {
            val tracked = git(root, "ls-files", "--error-unmatch", "--", path) != null
            if (tracked) {
                val output =
                    git(root, "diff", "--no-color", "--no-ext-diff", "-U3", "HEAD", "--", path)
                        ?: git(root, "diff", "--no-color", "--no-ext-diff", "-U3", "--cached", "--", path)
                UnifiedDiff().parse(output.orEmpty())
            } else {
                val lines = runCatching { File(root, path).readLines() }.getOrDefault(emptyList())
                UnifiedDiff.newFile(lines)
            }
        }

    private fun git(root: String, vararg args: String): List<String>? =
        runCatching {
            val process = ProcessBuilder(listOf("git") + args).directory(File(root)).start()
            val output = process.inputStream.bufferedReader().readLines()
            output.takeIf { process.waitFor() == 0 }
        }.getOrNull()

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
        const val SHORT_HASH = 8
        const val PATH_START = 3
    }
}
