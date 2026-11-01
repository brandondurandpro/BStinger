package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.state.ProjectState

// Bulk actions of the branch picker: tick several branches to pin, unpin or delete them, and the shortcut that
// deletes the branches without a remote. Every deletion goes through a confirmation listing what will be deleted.

/** Bar above the branch list: the ticked branches and what can be done with them, and the cleanup shortcut. */
@Composable
internal fun BranchBulkBar(
    project: ProjectState,
    matches: List<GitBranchInfo>,
    onDelete: () -> Unit,
    onCleanup: () -> Unit,
) {
    val picked = project.git.pickedBranches
    val withoutRemote = project.git.branchesWithoutRemote().size
    val active = picked.isNotEmpty()
    val barColor = if (active) IdeColors.Accent.copy(alpha = 0.10f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(barColor, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(GitTags.BRANCH_BULK_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) {
            Label(
                "${picked.size} selected",
                Modifier.testTag(GitTags.BRANCH_PICK_COUNT),
                color = IdeColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BarButton("Pin", GitTags.BRANCH_PIN_PICKED, enabled = !project.git.selectedBranches.containsAll(picked)) {
                project.git.pinPickedBranches()
                project.git.clearBranchPicks()
            }
            BarButton("Unpin", GitTags.BRANCH_UNPIN_PICKED, enabled = picked.any { it in project.git.selectedBranches }) {
                project.git.unpinPickedBranches()
                project.git.clearBranchPicks()
            }
            val deletable = picked.any { it != project.git.currentBranch }
            BarButton("Delete…", GitTags.BRANCH_DELETE_PICKED, enabled = deletable && !project.git.busy, danger = true) {
                onDelete()
            }
            BarButton("Clear", GitTags.BRANCH_CLEAR_PICKS) { project.git.clearBranchPicks() }
        } else {
            Label("Tick branches to pin or delete several at once", color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        val unpicked = matches.any { it.name !in picked }
        BarButton("Select all", GitTags.BRANCH_SELECT_ALL, enabled = unpicked) {
            project.git.pickBranches(matches.map { it.name })
        }
        BarButton(
            if (withoutRemote == 0) "No branch without remote" else "Clean up $withoutRemote without remote…",
            GitTags.BRANCH_CLEANUP,
            enabled = withoutRemote > 0 && !project.git.busy,
            danger = true,
        ) { onCleanup() }
    }
}

/**
 * Confirmation of a deletion: lists every branch that will be deleted, each one can be unticked, and nothing is
 * deleted before the user confirms. A failure keeps the dialog open with Git's reason; Escape cancels.
 */
@Composable
internal fun BranchDeleteDialog(
    project: ProjectState,
    request: BranchDeletion,
    onCancel: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val progress = remember(request) { DeletionProgress() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A branch deleted meanwhile (by a partial failure, or another program) leaves the list.
    val existing = project.git.branchInfos.mapTo(HashSet()) { it.name }
    val candidates = request.candidates.filter { it.name in existing }
    val toDelete = candidates.filter { it.name !in progress.unticked }.map { it.name }
    val cancel = { if (!progress.deleting) onCancel() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .onEscape(cancel)
            .focusRequester(focus)
            .focusable()
            .testTag(GitTags.BRANCH_DELETE_DIALOG),
    ) {
        DeletionIntro(request)
        DeletionList(candidates, progress.unticked, progress::toggle)
        if (progress.deleting) {
            BranchStatusLine("Deleting ${plural(toDelete.size, "branch", "branches")}…", GitTags.BRANCH_DELETING)
        }
        progress.failure?.let { BranchStatusLine(it, GitTags.BRANCH_ERROR, isError = true) }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(
                if (candidates.isEmpty()) {
                    "Nothing left to delete"
                } else {
                    "${toDelete.size} of ${plural(candidates.size, "branch", "branches")} will be deleted"
                },
                Modifier.testTag(GitTags.BRANCH_DELETE_SUMMARY),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            IdeButton(
                "Cancel",
                { cancel() },
                Modifier.testTag(GitTags.BRANCH_DELETE_CANCEL),
                enabled = !progress.deleting,
            )
            IdeButton(
                "Delete ${plural(toDelete.size, "branch", "branches")}",
                { progress.delete(project, toDelete, onDeleted) },
                Modifier.testTag(GitTags.BRANCH_DELETE_CONFIRM),
                primary = true,
                enabled = toDelete.isNotEmpty() && !progress.deleting && !project.git.busy,
            )
        }
    }
}

private fun Modifier.onEscape(action: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        if (escape) action()
        escape
    }

// The confirmation's state: the branches the user spared, and the deletion running or failed.
private class DeletionProgress {
    var unticked by mutableStateOf(emptySet<String>())
    var deleting by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)

    fun toggle(name: String) {
        unticked = if (name in unticked) unticked - name else unticked + name
    }

    fun delete(project: ProjectState, names: List<String>, onDeleted: (String) -> Unit) {
        deleting = true
        failure = null
        project.git.deleteBranches(names) { deleted ->
            deleting = false
            if (deleted) {
                onDeleted("${plural(names.size, "branch", "branches")} deleted")
            } else {
                failure = "Cannot delete every branch: ${project.git.message ?: "Git command failed"}"
            }
        }
    }
}

@Composable
private fun DeletionIntro(request: BranchDeletion) {
    Label(request.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Label(
        "${request.reason} Branches on the remote are not touched.",
        Modifier.padding(top = 4.dp),
        color = IdeColors.TextSecondary,
        fontSize = 11.sp,
    )
    request.keptCurrent?.let {
        Label(
            "$it is the current branch: it is kept.",
            Modifier.padding(top = 4.dp),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun DeletionList(candidates: List<GitBranchInfo>, unticked: Set<String>, onToggle: (String) -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .padding(top = 8.dp)
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(5.dp))
            .padding(4.dp),
    ) {
        items(candidates, key = { it.name }) { branch ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(branch.name) }
                    .testTag(GitTags.branchToDelete(branch.name))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BranchCheckbox(branch.name !in unticked, Modifier.testTag(GitTags.branchToDeleteCheck(branch.name))) {
                    onToggle(branch.name)
                }
                BranchIcon(Modifier.padding(start = 8.dp))
                Label(branch.name, Modifier.padding(start = 6.dp).weight(1f), fontSize = 12.sp)
                Label(
                    upstreamText(branch),
                    color = if (branch.upstreamGone) IdeColors.Error else IdeColors.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun BarButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color =
        when {
            !enabled -> IdeColors.LineNumber
            danger -> IdeColors.Error
            else -> IdeColors.Text
        }
    Box(
        Modifier
            .background(if (hovered && enabled) IdeColors.Hover else Color.Transparent, RoundedCornerShape(4.dp))
            .border(1.dp, if (enabled) IdeColors.InputBorder else IdeColors.Separator, RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, color = color, fontSize = 11.sp)
    }
}

/** Branches the user asked to delete, shown for confirmation before anything is deleted. */
internal class BranchDeletion(
    val title: String,
    val reason: String,
    val candidates: List<GitBranchInfo>,
    /** The current branch, when it was ticked: Git cannot delete it, so it is kept and the dialog says so. */
    val keptCurrent: String?,
) {
    companion object {
        fun ofPicked(project: ProjectState): BranchDeletion {
            val picked = project.git.branchInfos.filter { it.name in project.git.pickedBranches }
            val candidates = picked.filter { !it.current }
            return BranchDeletion(
                title = "Delete ${plural(candidates.size, "selected branch", "selected branches")}?",
                reason = "The local branches below are deleted, with the commits that only they hold.",
                candidates = candidates,
                keptCurrent = picked.firstOrNull { it.current }?.name,
            )
        }

        fun ofWithoutRemote(project: ProjectState): BranchDeletion {
            val candidates = project.git.branchesWithoutRemote()
            return BranchDeletion(
                title = "Delete ${plural(candidates.size, "branch", "branches")} without a remote?",
                reason = "These local branches track no remote branch, or one that was deleted on the remote.",
                candidates = candidates,
                keptCurrent = null,
            )
        }
    }
}
