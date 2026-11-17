package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import java.io.IOException

private const val WORKTREE_LOAD_MILLIS = 2_000L

/**
 * Asks the server for the worktree of [branch] (created on first use) and opens it as a project, returned once its
 * tree and branch are loaded so that the tool windows never flash empty.
 */
internal suspend fun ProjectState.openWorktreeProject(
    branch: String,
    base: String?,
    toolWindows: ToolWindowLayout,
): ProjectState {
    val path = services.git.worktree(root.path, branch, base)
    val directory = checkNotNull(services.fileSystem.resolveDirectory(path)) { "$path is missing" }
    return openFolderProject(directory, toolWindows)
}

/**
 * Opens [directory] as a project of this window, returned once its tree and branch are loaded so that the tool windows
 * never flash empty: a worktree of this project, or another folder a conversation works in.
 */
internal suspend fun ProjectState.openFolderProject(directory: FileEntry, toolWindows: ToolWindowLayout): ProjectState {
    // The tool windows of the agent, the same open in the folder as in the project it works on directly.
    val opened =
        ProjectState(
            directory,
            services,
            scope,
            toolWindows = toolWindows,
            grammars = grammars,
            diffLayout = diffLayout,
            statusIndicators = statusIndicators,
            installedPlugins = installedPlugins,
        )
    withTimeoutOrNull(WORKTREE_LOAD_MILLIS) {
        snapshotFlow { opened.tree.rows.size > 1 && opened.git.currentBranch != null }.first { it }
    }
    return opened
}

/** The workspace of [Conversation.workspace] (the agent's own, or a worktree) as a project, if it is one. */
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
