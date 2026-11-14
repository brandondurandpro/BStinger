package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val WORKTREE_LOAD_MILLIS = 2_000L

/**
 * Asks the server for the worktree of [branch] (created on first use) and opens it as a project, returned once its
 * tree and branch are loaded so that the tool windows never flash empty.
 */
internal suspend fun ProjectState.openWorktreeProject(branch: String, base: String?): ProjectState {
    val path = services.git.worktree(root.path, branch, base)
    val directory = checkNotNull(services.fileSystem.resolveDirectory(path)) { "$path is missing" }
    // Same tool windows open, whether the agent works on the project or in its worktree.
    val worktree =
        ProjectState(
            directory,
            services,
            scope,
            toolWindows = toolWindows,
            grammars = grammars,
            diffLayout = diffLayout,
            statusIndicators = statusIndicators,
        )
    withTimeoutOrNull(WORKTREE_LOAD_MILLIS) {
        snapshotFlow { worktree.tree.rows.size > 1 && worktree.git.currentBranch != null }.first { it }
    }
    return worktree
}

/** The project or worktree [Conversation.workspace] is: the IDE shows its tool windows and editors. */
internal val Conversation.workspaceProject: ProjectState? get() = workspace as? ProjectState

/** The branches of the repository of this project, after a fetch when [fetch]; null outside a Git repository. */
internal suspend fun ProjectState.branchChoicesOf(fetch: Boolean): BranchChoices? {
    val git = services.git
    val current = git.branch(root.path) ?: return null
    val fetchError = if (fetch) fetchFailure() else null
    val local = git.branches(root.path)
    val remote = git.remoteBranches(root.path)
    val defaultRemote = runCatching { git.defaultRemoteBranch(root.path) }.getOrNull()
    return BranchChoices(current, local, remote, fetchError, defaultRemote)
}

// Why the remotes could not be fetched (no network, no remote): the branches already known stay usable.
private suspend fun ProjectState.fetchFailure(): String? =
    try {
        services.git.fetch(root.path)
        null
    } catch (failed: IOException) {
        failed.message ?: "The remotes could not be fetched"
    }
