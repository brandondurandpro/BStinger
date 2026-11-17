package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry

// Choosing the folder a conversation works in, before its first prompt: any folder of the server. One under the
// project is where the agent works in this window; another one is a project of its own, in a new window.

/** The folders the field of the folder offers: those of [directory] the user typed the beginning of. */
class FolderListing(
    /** The folder listed. */
    val directory: String,
    /** Its folders that start with what was typed after it, by name. */
    val folders: List<FileEntry>,
    /** Why it could not be listed; null when it was. */
    val failure: String? = null,
)

/**
 * The folders to offer [conversation] for what is typed in its field [typed]: with a separator at the end (or nothing typed) all those
 * of the folder it names, else those of its parent that start with its last name. Folders whose name starts with a dot
 * are left out unless a dot was typed. A folder that cannot be read says why, with none to offer.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun AgentState.listFolders(conversation: Conversation, typed: String): FolderListing {
    val root = conversation.rootPath(project)
    val resolved = resolveFolder(root, typed)
    val open = typed.isBlank() || typed.endsWith("/") || typed.endsWith("\\")
    val directory = if (open) resolved else parentFolder(resolved) ?: resolved
    val start = if (open) "" else typed.trim().split('/', '\\').lastOrNull().orEmpty()
    return try {
        val entry = project.services.fileSystem.resolveDirectory(directory)
        if (entry == null) {
            FolderListing(directory, emptyList(), "$directory is not a folder.")
        } else {
            val children = project.services.fileSystem.list(entry)
            val shown = children.filter { it.isDirectory && it.name.startsWith(start, ignoreCase = true) }
            val visible = if (start.startsWith(".")) shown else shown.filter { !it.name.startsWith(".") }
            FolderListing(directory, visible.sortedBy { it.name.lowercase() })
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        FolderListing(directory, emptyList(), "$directory cannot be read: ${failure.message}")
    }
}

/**
 * Chooses the folder [typed] (absolute, or under the folder the conversation works in; empty is its root) for
 * [conversation], which has not started: under that folder, the agent works there; elsewhere, the conversation moves to
 * that folder, which this window opens as a project (its branches, its worktrees, its files are its own). A folder that
 * does not exist is told in [Conversation.folderError], and nothing changes. The job ends once it is settled.
 */
@Suppress("TooGenericExceptionCaught")
fun AgentState.chooseFolder(conversation: Conversation, typed: String): Job {
    if (conversation.started || conversation.busy) return Job().apply { complete() }
    conversation.folderError = null
    val root = conversation.rootPath(project)
    val path = resolveFolder(root, typed)
    val relative = relativeFolder(root, path)
    return project.scope.launch {
        val failure =
            try {
                if (project.services.fileSystem.resolveDirectory(path) == null) "$path is not a folder." else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "$path cannot be read: ${failed.message}"
            }
        when {
            failure != null -> conversation.folderError = failure
            relative != null -> conversation.subdirectory = relative
            else -> moveToFolder(conversation, path)
        }
    }
}

/** The Open Project dialog, to browse the server's disk for the folder of [conversation]: no project opens. */
fun AgentState.browseFolder(conversation: Conversation) {
    project.browseFolderForConversation { path -> chooseFolder(conversation, path) }
}

/**
 * Removes the folder [folder] from the list of the conversations: its conversations close and the project this window
 * opened for it is released. Nothing is deleted: the agent keeps their sessions and the worktrees stay on the disk; they
 * come back with the folder when a conversation works in it again. The project of the window cannot be removed.
 */
fun AgentState.removeFolder(folder: String) {
    if (folder == project.root.path) return
    forgottenFolders.add(folder)
    closeConversations(conversations.filter { it.folder == folder })
    project.closeFolder(folder)
}

/**
 * Removes the group of the project of this window from the list: its conversations close, the agent keeps their
 * sessions. The project stays the window's own; a conversation started in it brings the group back.
 */
fun AgentState.removeProjectGroup() {
    closeConversations(conversations.filter { it.folder == null && !it.scratch })
}

/**
 * Removes the group without a project from the list: its conversations close, the agent keeps their sessions, and their
 * empty folders stay on the server.
 */
fun AgentState.removeScratchGroup() {
    closeConversations(conversations.filter { it.scratch })
}

// Closes [closing] in this window; nothing is deleted: the agent keeps the sessions, Git the worktrees and their changes.
private fun AgentState.closeConversations(closing: List<Conversation>) {
    val agents = project.services.agents
    for (conversation in closing) {
        conversation.job?.cancel()
        conversation.polling?.cancel()
        conversation.stopFollowingMembers()
        tabs.close(conversation)
        conversations.remove(conversation)
        val started = conversation.started
        project.scope.launch { if (started) runCatching { agents.close(conversation.key) } }
        // Only this window's view of a worktree closes.
        conversation.closeWorktree(conversation.host(project))
    }
}

// A conversation works in [path]: a folder the user removed is listed again, with the sessions it still has.
internal fun AgentState.rememberFolder(path: String) {
    if (!forgottenFolders.remove(path)) return
    project.scope.launch { historyOf(path, false) }
}

// The conversation, which has not started, works in the folder [path] of the server: this window opens it as a project,
// and the conversation starts again there, in its root, on the project's branch.
private suspend fun AgentState.moveToFolder(conversation: Conversation, path: String) {
    val opened = runCatching { project.openFolder(path) }.getOrNull()
    if (opened == null) {
        conversation.folderError = "$path cannot be opened as a folder."
        return
    }
    val own = opened.takeIf { it !== project }
    // The branches of the folder's repository, read before anything changes: the conversation then moves and asks at once.
    val choices = runCatching { opened.branchChoices(fetch = false) }.getOrNull()
    // Only before the first prompt: nothing of the agent runs there yet.
    if (conversation.started || conversation.busy) return
    conversation.closeWorktree(conversation.host(project))
    conversation.mode = WorkMode.Direct
    conversation.base = null
    conversation.workspaceError = null
    conversation.home = own
    conversation.folder = own?.root?.path
    conversation.scratch = false
    conversation.folder?.let(::rememberFolder)
    conversation.subdirectory = ""
    conversation.prepareIn(project)
    // In a Git repository: from which branch does the agent work?
    if (choices != null) showBasePicker(own, conversation, choices)
}
