package org.example.kotlineditor.state

import kotlinx.coroutines.CoroutineScope
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry

/** Where the agent of a conversation works, as agent mode sees it: the project itself, or a worktree of a branch. */
interface AgentWorkspace {
    val root: FileEntry

    /** The files and diffs opened under the conversation. */
    val editor: EditorState

    /** Its files, for the `@` mentions of the prompt. */
    val fileIndex: FileIndex

    /** The branch checked out there, once Git said it. */
    val checkedOutBranch: String?

    /** Looks at the disk again (tree, Git, open editors) after the agent changed files. */
    fun synchronize()

    /** Closes the workspace; never called on the project itself. */
    fun dispose()
}

/** The project of agent mode: the server it reaches, and the worktrees it opens as workspaces. */
interface AgentHost : AgentWorkspace {
    val services: AppServices

    /** Cancelled when the project is closed. */
    val scope: CoroutineScope

    /** True while the window shows agent mode instead of the editors. */
    val agentMode: Boolean

    /** The worktree of [branch], created by the server on first use, returned once it is loaded. */
    suspend fun openWorktree(branch: String): AgentWorkspace

    /** Chooses the agent [entry] of the ACP registry the next conversations of agent mode will run. */
    fun makeDefaultAgent(entry: String)

    /** The id of the agent of the ACP registry the past [session] ran with, remembered; null: it is unknown, or it
     * was the agent of the settings. */
    fun rememberedAgent(session: String): String?

    /** Remembers that [session] ran with the agent [entry] of the registry; null forgets it (the settings' one). */
    fun rememberAgentOf(session: String, entry: String?)

    /** The ids of the agents of the registry remembered, the recent ones first. */
    val rememberedAgents: List<String>
}
