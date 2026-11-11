package org.example.kotlineditor.state

import org.example.kotlineditor.model.GitResetMode

/**
 * A dialog opened by a context menu of the Branches window. With an [input] (the initial text) the user types a name;
 * [submit] gets it and tells through its callback whether the action worked, so that a failure stays in the dialog.
 */
class HistoryDialog(
    val title: String,
    val message: String?,
    val input: String?,
    val confirmLabel: String,
    val danger: Boolean = false,
    val submit: (text: String, onDone: (Boolean) -> Unit) -> Unit,
)

/** Creates [name] at [startPoint] (a branch or a commit hash) and checks it out. */
fun GitState.createBranchAt(startPoint: String, name: String, onDone: (Boolean) -> Unit) =
    operate(reloadFiles = true, onDone) { git, root ->
        git.createBranch(root, name, startPoint, switchTo = true)
        "Created $name"
    }

fun GitState.renameBranch(oldName: String, newName: String, onDone: (Boolean) -> Unit) =
    operate(reloadFiles = false, onDone) { git, root ->
        git.renameBranch(root, oldName, newName)
        "Renamed $oldName to $newName"
    }

fun GitState.checkoutRemoteBranch(remoteBranch: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root ->
        val local = git.checkoutRemote(root, remoteBranch)
        "Switched to $local"
    }

fun GitState.checkoutCommit(hash: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root ->
        git.checkoutCommit(root, hash)
        "Checked out ${hash.take(SHORT)} (detached HEAD)"
    }

fun GitState.createTag(name: String, hash: String, onDone: (Boolean) -> Unit) =
    operate(reloadFiles = false, onDone) { git, root ->
        git.createTag(root, name, hash)
        "Tagged ${hash.take(SHORT)} as $name"
    }

fun GitState.mergeBranch(branch: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root -> git.merge(root, branch).ifBlank { "Merged $branch" } }

fun GitState.rebaseOnto(branch: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root -> git.rebase(root, branch).ifBlank { "Rebased onto $branch" } }

fun GitState.cherryPickCommit(hash: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root ->
        git.cherryPick(root, hash).ifBlank { "Cherry-picked ${hash.take(SHORT)}" }
    }

fun GitState.revertCommit(hash: String, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root ->
        git.revertCommit(root, hash).ifBlank { "Reverted ${hash.take(SHORT)}" }
    }

fun GitState.resetTo(hash: String, mode: GitResetMode, onDone: (Boolean) -> Unit = {}) =
    operate(reloadFiles = true, onDone) { git, root ->
        git.reset(root, hash, mode).ifBlank { "Reset to ${hash.take(SHORT)} (${mode.name.lowercase()})" }
    }

private const val SHORT = 8
