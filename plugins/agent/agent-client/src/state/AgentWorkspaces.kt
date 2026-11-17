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

/** The project the conversation works in: the one of its folder, or the project of the window. */
internal fun Conversation.host(project: AgentHost): AgentHost = home ?: project

/** The root of the project the conversation works in. */
internal fun Conversation.rootPath(project: AgentHost): String = folder ?: project.root.path

// Closes the worktree project of the conversation (never the project itself) and returns it.
internal fun Conversation.closeWorktree(project: AgentHost): AgentWorkspace? {
    preparing?.cancel()
    val worktree = workspace?.takeIf { it !== project && it !== project.agentWorkspace() }
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
    val known = if (folder == null) project else home
    if (mode == WorkMode.Direct && known != null) {
        workspace = known.agentWorkspace()
        return
    }
    preparing =
        project.scope.launch {
            // A conversation of the history in another folder: its project opens first.
            val repository = known ?: openHome(project)
            if (repository == null) return@launch
            runCatching { if (mode == WorkMode.Direct) repository.agentWorkspace() else repository.openWorktree(branch, base) }
                .onSuccess { workspace = it }
                .onFailure { workspaceError = it.message ?: "The worktree could not be created" }
        }
}

// The folder of the conversation as a project of the window; null, and the error said, when it cannot be opened.
private suspend fun Conversation.openHome(project: AgentHost): AgentHost? {
    val path = folder ?: return project
    val opened = runCatching { project.openFolder(path) }.getOrNull()
    if (opened == null) workspaceError = "The folder $path cannot be opened"
    home = opened
    return opened
}
