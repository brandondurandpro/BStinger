package org.example.kotlineditor.state

import kotlinx.coroutines.launch

// Where the agent of a conversation works: a workspace of its own on the project, or a worktree of its branch.

/** Shows [workspace] for this conversation and closes the worktree it showed before, never what is shared. */
internal fun Conversation.show(workspace: AgentWorkspace, project: AgentHost) {
    val old = this.workspace
    this.workspace = workspace
    // The project and the agent's workspace on it are shared by the conversations: only a worktree is closed.
    val shared = old === project || old === project.agentWorkspace()
    if (old != null && !shared && old !== workspace) old.dispose()
}

// Closes the worktree project of the conversation (never the project itself) and returns it.
internal fun Conversation.closeWorktree(project: AgentHost): AgentWorkspace? {
    preparing?.cancel()
    val worktree = workspace?.takeIf { it !== project }
    worktree?.dispose()
    workspace = null
    workspaceError = null
    return worktree
}

// The agent's workspace on the project in direct mode; otherwise the worktree of the branch, created by the server
// on first use.
internal fun Conversation.prepareIn(project: AgentHost) {
    if (workspace != null || preparing?.isActive == true) return
    workspaceError = null
    if (mode == WorkMode.Direct) {
        workspace = project.agentWorkspace()
        return
    }
    preparing =
        project.scope.launch {
            runCatching { project.openWorktree(branch, base) }
                .onSuccess { workspace = it }
                .onFailure { workspaceError = it.message ?: "The worktree could not be created" }
        }
}
