package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.WorktreeClient
import java.io.File

private const val GITDIR_PREFIX = "gitdir:"
private const val WORKTREE = "worktree"
private const val WORKTREE_PREFIX = "$WORKTREE "
private const val BRANCH_PREFIX = "branch refs/heads/"

/** Directory of the Git data of [root]: `.git`, or the directory a worktree's `.git` file points to. */
internal fun gitDirectory(root: String): File {
    val dotGit = File(root, ".git")
    val target = if (dotGit.isFile) runCatching { dotGit.readText().trim() }.getOrDefault("") else ""
    val path = File(target.removePrefix(GITDIR_PREFIX).trim())
    return when {
        !target.startsWith(GITDIR_PREFIX) -> dotGit
        path.isAbsolute -> path
        else -> File(root, path.path)
    }
}

/**
 * The worktrees of agent mode of the repository of [root]: their directory and their branch. Empty outside a Git
 * repository.
 */
internal fun agentWorktrees(root: String): Map<String, String> {
    val common = gitOutput(root, "rev-parse", "--path-format=absolute", "--git-common-dir")?.firstOrNull()
    val prefix = common?.let { File(it, AGENT_WORKTREES).path + File.separator } ?: return emptyMap()
    val found = LinkedHashMap<String, String>()
    var path: String? = null
    for (line in gitOutput(root, WORKTREE, "list", "--porcelain").orEmpty()) {
        when {
            line.startsWith(WORKTREE_PREFIX) -> {
                path = File(line.removePrefix(WORKTREE_PREFIX)).path
            }

            line.startsWith(BRANCH_PREFIX) -> {
                val agentWorktree = path?.takeIf { it.startsWith(prefix) }
                if (agentWorktree != null) found[agentWorktree] = line.removePrefix(BRANCH_PREFIX)
            }
        }
    }
    return found
}

// Where the worktrees of agent mode live, in the Git directory of the repository.
private const val AGENT_WORKTREES = "kotlin-editor/worktrees"

/** Worktrees of the branches the agent works on, kept in the Git directory so they never show in the project. */
internal class CliWorktrees : WorktreeClient {
    override suspend fun worktree(root: String, branch: String): String =
        withContext(Dispatchers.IO) { existing(root, branch) ?: create(root, branch) }

    override suspend fun removeWorktree(root: String, path: String) {
        withContext(Dispatchers.IO) { runGit(root, WORKTREE, "remove", path) }
    }

    private fun create(root: String, branch: String): String {
        val common = runGit(root, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()
        val directory = File(common, "$AGENT_WORKTREES/${branch.replace(UNSAFE, "-")}")
        val known = gitOutput(root, "show-ref", "--verify", "--quiet", "refs/heads/$branch") != null
        if (known) {
            runGit(root, WORKTREE, "add", directory.path, branch)
        } else {
            runGit(root, WORKTREE, "add", "-b", branch, directory.path)
        }
        return directory.path
    }

    // `git worktree list --porcelain`: blocks of `worktree <path>`, `HEAD <hash>`, `branch refs/heads/<name>`.
    private fun existing(root: String, branch: String): String? {
        var path: String? = null
        for (line in gitOutput(root, WORKTREE, "list", "--porcelain").orEmpty()) {
            when {
                line.startsWith(WORKTREE_PREFIX) -> path = line.removePrefix(WORKTREE_PREFIX)
                line == BRANCH_PREFIX + branch && path != null && File(path).isDirectory -> return path
            }
        }
        return null
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
