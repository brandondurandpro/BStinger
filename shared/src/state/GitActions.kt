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
    scope.launch {
        gitOperation {
            services.git.commit(root.path, message.trim())
            commitMessage = ""
            "Committed"
        }
    }
}

/** Checks out a local branch (the branch menu of the Git panel). */
fun ProjectState.checkoutBranch(branch: String, onDone: (Boolean) -> Unit = {}) {
    if (branch == gitBranch) {
        onDone(true)
        return
    }
    scope.launch { onDone(checkout(branch)) }
}

/**
 * Checks out [branch] and returns whether it worked; a failure stays in [gitMessage] so the caller can show it (the
 * branch picker keeps its panel open and explains why, instead of closing as if the checkout had happened).
 */
suspend fun ProjectState.checkout(branch: String): Boolean {
    if (branch == gitBranch) return true
    return gitOperation(reloadFiles = true) {
        services.git.checkout(root.path, branch)
        "Switched to $branch"
    }
}

/** Pulls the current branch (Git panel's Pull button). */
fun ProjectState.pull() =
    scope.launch {
        gitOperation(reloadFiles = true) {
            services.git.pull(root.path).ifBlank { "Pulled" }
        }
    }

/** Pushes the current branch (Git panel's Push button). */
fun ProjectState.push() =
    scope.launch {
        gitOperation {
            services.git.push(root.path).ifBlank { "Pushed" }
        }
    }

/** Shows or hides the blame annotations of the active editor (Git panel's Annotate button). */
fun ProjectState.toggleBlame() = editor.toggleBlame(editor.activeTab)

// Runs a Git action once at a time: saves the editors first, then reports Git's output (or its error) in the panel.
// Returns whether the action ran and succeeded, for callers that wait for it.
private suspend fun ProjectState.gitOperation(reloadFiles: Boolean = false, block: suspend () -> String): Boolean {
    if (gitBusy) return false
    gitBusy = true
    gitMessage = null
    gitMessageIsError = false
    return try {
        editor.flush()
        val message = block().trim().replace('\n', ' ').take(MAX_MESSAGE_LENGTH)
        gitMessage = message.ifBlank { "Done" }
        if (reloadFiles) synchronize() else refreshGit()
        true
    } catch (e: IOException) {
        gitMessage = e.message ?: "Git command failed"
        gitMessageIsError = true
        refreshGitStatus()
        false
    } finally {
        gitBusy = false
    }
}
