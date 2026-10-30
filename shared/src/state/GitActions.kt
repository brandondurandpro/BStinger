package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import java.io.IOException

private const val MAX_MESSAGE_LENGTH = 300

/** Commits every change of the project with [message] (the Git panel's Commit button). */
fun ProjectState.commitChanges(message: String) {
    if (message.isBlank()) {
        gitMessage = "A commit message is required"
        gitMessageIsError = true
        return
    }
    gitOperation {
        services.git.commit(root.path, message.trim())
        commitMessage = ""
        "Committed"
    }
}

/** Checks out a local branch (the branch menu of the Git panel). */
fun ProjectState.checkoutBranch(branch: String) {
    if (branch == gitBranch) return
    gitOperation(reloadFiles = true) {
        services.git.checkout(root.path, branch)
        "Switched to $branch"
    }
}

/** Pulls the current branch (Git panel's Pull button). */
fun ProjectState.pull() =
    gitOperation(reloadFiles = true) {
        services.git.pull(root.path).ifBlank { "Pulled" }
    }

/** Pushes the current branch (Git panel's Push button). */
fun ProjectState.push() =
    gitOperation {
        services.git.push(root.path).ifBlank { "Pushed" }
    }

/** Shows or hides the blame annotations of the active editor (Git panel's Annotate button). */
fun ProjectState.toggleBlame() = editor.toggleBlame(editor.activeTab)

// Runs a Git action once at a time: saves the editors first, then reports Git's output (or its error) in the panel.
private fun ProjectState.gitOperation(reloadFiles: Boolean = false, block: suspend () -> String) {
    if (gitBusy) return
    scope.launch {
        gitBusy = true
        gitMessage = null
        gitMessageIsError = false
        try {
            editor.flush()
            val message = block().trim().replace('\n', ' ').take(MAX_MESSAGE_LENGTH)
            gitMessage = message.ifBlank { "Done" }
            if (reloadFiles) synchronize() else refreshGit()
        } catch (e: IOException) {
            gitMessage = e.message ?: "Git command failed"
            gitMessageIsError = true
            refreshGitStatus()
        } finally {
            gitBusy = false
        }
    }
}
