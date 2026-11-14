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

    /** Shows the tool window [window] (the files, the terminal…) of this workspace. */
    fun showToolWindow(window: ToolWindow)

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

    /**
     * The worktree of [branch], created by the server on first use from [base] (HEAD without it), returned once it is
     * loaded.
     */
    suspend fun openWorktree(branch: String, base: String? = null): AgentWorkspace

    /**
     * The branches a conversation can start from, null when the project is not a Git repository. With [fetch] the
     * remote branches are fetched first; a fetch that fails (offline) leaves the ones already known and says why in
     * [BranchChoices.fetchError].
     */
    suspend fun branchChoices(fetch: Boolean): BranchChoices? = null

    /** An agent finished its turn in the conversation [title]; [failed] when it ended with an error. */
    fun agentFinished(title: String, failed: Boolean) = Unit

    /** An agent asks the user for a permission in the conversation [title]: [question] says what it wants to do. */
    fun agentAsking(title: String, question: String) = Unit
}

/** The branches of the project a conversation can start from: the one checked out, the local ones, the remote ones. */
class BranchChoices(
    val current: String?,
    val local: List<String>,
    val remote: List<String>,
    val fetchError: String? = null,
    /** The default branch of the remote (`origin/main`): the one the picker highlights first. */
    val defaultRemote: String? = null,
)
