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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.state.BranchSync
import org.example.kotlineditor.state.GitHost

/** Calls [onPress] on a right click, without consuming it, so the row keeps its own click handling. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.button == PointerButton.Secondary) onPress()
    }

/**
 * Right-click menu of a branch of the Branches window. [local] is null for a remote-tracking branch. Items that make
 * no sense now are disabled rather than hidden: the current branch cannot be deleted or checked out again.
 */
@Composable
internal fun BranchContextMenu(project: GitHost, name: String, local: GitBranchInfo?, onClose: () -> Unit) {
    val git = project.git
    val isLocal = local != null
    val idle = !git.busy
    val run = { block: () -> Unit ->
        onClose()
        block()
    }
    IdeDropdownMenu(
        expanded = true,
        onDismissRequest = onClose,
        modifier = Modifier.testTag(GitTags.BRANCH_CONTEXT_MENU),
    ) {
        MenuEntry("Checkout", "checkout", enabled = canCheckout(local) && idle) { run { git.checkoutBranch(name) } }
        MenuEntry("Push", "push", enabled = isLocal && idle) { run { git.syncBranch(name, BranchSync.Action.Push) } }
        MenuEntry("Pull", "pull", enabled = local?.upstream != null && idle) {
            run { git.syncBranch(name, BranchSync.Action.Pull) }
        }
        MenuEntry("Fetch", "fetch", enabled = idle) { run { git.fetchNow() } }
        DeleteEntry(name, enabled = canCheckout(local) && idle) { run { git.deleteBranches(listOf(name)) } }
        MenuEntry("Copy branch name", "copyName") { run { TextClipboard.set(name) } }
    }
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
        MenuEntry("Show changes", "showChanges") { run { project.git.history.selectCommit(commit) } }
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
        text = { Text(text, fontSize = 13.sp, color = if (danger) IdeColors.Error else IdeColors.Text) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(30.dp).testTag(GitTags.historyMenuItem(action)),
    )
}
