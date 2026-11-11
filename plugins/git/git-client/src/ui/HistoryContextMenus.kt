package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitResetMode
import org.example.kotlineditor.state.BranchSync
import org.example.kotlineditor.state.GitHost
import org.example.kotlineditor.state.checkoutCommit
import org.example.kotlineditor.state.checkoutRemoteBranch
import org.example.kotlineditor.state.cherryPickCommit
import org.example.kotlineditor.state.mergeBranch
import org.example.kotlineditor.state.resetTo
import org.example.kotlineditor.state.revertCommit

/** Calls [onPress] on a right click, without consuming it, so the row keeps its own click handling. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.button == PointerButton.Secondary) onPress()
    }

/**
 * Right-click menu of a branch of the Branches window. [local] is null for a remote-tracking branch. Items that make
 * no sense now are disabled rather than hidden: the current branch cannot be deleted, merged into itself or checked
 * out again.
 */
@Composable
internal fun BranchContextMenu(project: GitHost, name: String, local: GitBranchInfo?, onClose: () -> Unit) {
    val run = { block: () -> Unit ->
        onClose()
        block()
    }
    IdeDropdownMenu(
        expanded = true,
        onDismissRequest = onClose,
        modifier = Modifier.testTag(GitTags.BRANCH_CONTEXT_MENU),
    ) {
        BranchSwitchItems(project, name, local, run)
        BranchIntegrateItems(project, name, local, run)
        BranchRemoteItems(project, name, local, run)
        DeleteEntry(name, enabled = canCheckout(local) && !project.git.busy) {
            run { project.git.deleteBranches(listOf(name)) }
        }
        MenuEntry("Copy branch name", "copyName") { run { TextClipboard.set(name) } }
    }
}

@Composable
private fun BranchSwitchItems(project: GitHost, name: String, local: GitBranchInfo?, run: (() -> Unit) -> Unit) {
    val git = project.git
    val idle = !git.busy
    if (local == null) {
        MenuEntry("Checkout as local branch", "checkoutRemote", enabled = idle) {
            run { git.checkoutRemoteBranch(name) }
        }
    } else {
        MenuEntry("Checkout", "checkout", enabled = canCheckout(local) && idle) { run { git.checkoutBranch(name) } }
    }
    MenuEntry("New branch from here…", "newBranch", enabled = idle) {
        run { project.git.history.dialog = newBranchDialog(project, name) }
    }
    if (local != null) {
        MenuEntry("Rename…", "rename", enabled = idle) { run { git.history.dialog = renameDialog(project, name) } }
    }
}

@Composable
private fun BranchIntegrateItems(project: GitHost, name: String, local: GitBranchInfo?, run: (() -> Unit) -> Unit) {
    val git = project.git
    val canIntegrate = local?.current != true && !git.busy
    MenuEntry("Merge into current", "merge", enabled = canIntegrate) { run { git.mergeBranch(name) } }
    MenuEntry("Rebase current onto this…", "rebase", enabled = canIntegrate) {
        run { git.history.dialog = rebaseDialog(project, name) }
    }
}

@Composable
private fun BranchRemoteItems(project: GitHost, name: String, local: GitBranchInfo?, run: (() -> Unit) -> Unit) {
    val git = project.git
    val idle = !git.busy
    MenuEntry("Push", "push", enabled = local != null && idle) { run { git.syncBranch(name, BranchSync.Action.Push) } }
    MenuEntry("Pull", "pull", enabled = local?.upstream != null && idle) {
        run { git.syncBranch(name, BranchSync.Action.Pull) }
    }
    MenuEntry("Fetch", "fetch", enabled = idle) { run { git.fetchNow() } }
}

// A local branch other than the checked-out one: what can be checked out, and what can be deleted.
private fun canCheckout(local: GitBranchInfo?): Boolean = local != null && !local.current

// The item asks again before it deletes, as the Revert of the changes menu does.
@Composable
private fun DeleteEntry(name: String, enabled: Boolean, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val text = if (confirming) "Sure? Delete $name" else "Delete…"
    MenuEntry(text, "delete", enabled = enabled, danger = confirming) {
        if (confirming) onDelete() else confirming = true
    }
}

/** Right-click menu of a commit of the Branches window. */
@Composable
internal fun CommitContextMenu(project: GitHost, commit: GitCommit, onClose: () -> Unit) {
    val git = project.git
    val idle = !git.busy
    val isMerge = commit.parents.size > 1
    val run = { block: () -> Unit ->
        onClose()
        block()
    }
    IdeDropdownMenu(
        expanded = true,
        onDismissRequest = onClose,
        modifier = Modifier.testTag(GitTags.COMMIT_CONTEXT_MENU),
    ) {
        MenuEntry("Copy commit hash", "copyHash") { run { TextClipboard.set(commit.hash) } }
        MenuEntry("Show changes", "showChanges") { run { git.history.selectCommit(commit) } }
        MenuEntry(
            "Checkout (detached HEAD)",
            "checkoutCommit",
            enabled = idle,
        ) { run { git.checkoutCommit(commit.hash) } }
        MenuEntry("New branch from here…", "newBranch", enabled = idle) {
            run { git.history.dialog = newBranchDialog(project, commit.hash) }
        }
        MenuEntry(
            "New tag here…",
            "newTag",
            enabled = idle,
        ) { run { git.history.dialog = newTagDialog(project, commit) } }
        // A merge commit needs the parent to follow, which this menu cannot ask for.
        MenuEntry("Cherry-pick", "cherryPick", enabled = idle && !isMerge) { run { git.cherryPickCommit(commit.hash) } }
        MenuEntry("Revert", "revert", enabled = idle && !isMerge) { run { git.revertCommit(commit.hash) } }
        ResetItems(project, commit, run)
    }
}

@Composable
private fun ResetItems(project: GitHost, commit: GitCommit, run: (() -> Unit) -> Unit) {
    val idle = !project.git.busy
    MenuEntry("Reset current branch to here (soft)", "resetSoft", enabled = idle) {
        run { project.git.resetTo(commit.hash, GitResetMode.Soft) }
    }
    MenuEntry("Reset current branch to here (mixed)", "resetMixed", enabled = idle) {
        run { project.git.resetTo(commit.hash, GitResetMode.Mixed) }
    }
    MenuEntry("Reset current branch to here (hard)…", "resetHard", enabled = idle) {
        run { project.git.history.dialog = hardResetDialog(project, commit) }
    }
}

@Composable
private fun MenuEntry(
    text: String,
    action: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    IdeMenuItem(
        text = { Text(text, fontSize = 13.sp, color = if (danger) IdeColors.Error else Color.Unspecified) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(30.dp).testTag(GitTags.historyMenuItem(action)),
    )
}
