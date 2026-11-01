package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Where the agent of a conversation works: the project itself, or a worktree of its branch opened as a project.

private const val WORKTREE_LOAD_MILLIS = 2_000L

/**
 * Asks the server for the worktree of [branch] (created on first use) and opens it as a project, returned once its
 * tree and branch are loaded so that the tool windows never flash empty.
 */
internal suspend fun ProjectState.openWorktree(branch: String): ProjectState {
    val path = services.git.worktree(root.path, branch)
    val directory = checkNotNull(services.fileSystem.resolveDirectory(path)) { "$path is missing" }
    // Same tool windows open, whether the agent works on the project or in its worktree.
    val worktree = ProjectState(directory, services, scope, toolWindows = toolWindows, grammars = grammars)
    withTimeoutOrNull(WORKTREE_LOAD_MILLIS) {
        snapshotFlow { worktree.tree.rows.size > 1 && worktree.gitBranch != null }.first { it }
    }
    return worktree
}

/** Shows [workspace] for this conversation and closes the worktree it showed before, never [project] itself. */
internal fun Conversation.show(workspace: ProjectState, project: ProjectState) {
    val old = this.workspace
    this.workspace = workspace
    val replaced = old !== project && old !== workspace
    if (old != null && replaced) old.dispose()
}
