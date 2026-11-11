package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.state.BranchSync
import org.example.kotlineditor.state.GitHost
import org.example.kotlineditor.state.ToolWindow

// The branch widget of the toolbar: the current branch (opens the branch picker), its Pull button, the pinned
// branches and their menu (checkout, pull, push, changes), and the result of the last pull or push.

@Composable
fun BranchWidget(project: GitHost, modifier: Modifier = Modifier) {
    val current = project.git.branchInfos.firstOrNull { it.current }
    val infos = project.git.branchInfos
    val selected = project.git.selectedBranches
    // In the picker's order, without the current branch; computed once per change, not per frame.
    val pinned = remember(infos, selected) { infos.filter { it.name in selected && !it.current } }
    val background = if (project.git.branchPickerOpen) IdeColors.Selection else Color.Transparent
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .background(background, IdeShapes.Medium)
                .clickable { project.git.branchPickerOpen = true }
                .testTag(GitTags.GIT_BRANCH)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchIcon(color = if (project.git.branchPickerOpen) IdeColors.Accent else IdeColors.TextSecondary)
            Label(
                project.git.currentBranch.orEmpty(),
                Modifier.padding(start = 5.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            current?.let { BranchSyncBadge(it, compact = true, modifier = Modifier.padding(start = 6.dp)) }
            Chevron(expanded = false, Modifier.padding(start = 2.dp))
        }
        current?.let { QuickPullButton(project, it) }
        // Lazy: "Select all" then "Pin" can pin thousands of branches, and only the visible chips are composed.
        LazyRow(
            Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(pinned, key = { it.name }) { branch -> PinnedBranchChip(project, branch) }
        }
        project.git.branchSync?.let { BranchSyncStatus(project, it) }
    }
}

// Pulls the current branch in one click, fast-forward only: a branch that diverged from its remote is refused with
// Git's reason, never merged behind the user's back. Highlighted when the last fetch found commits to pull.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPullButton(project: GitHost, current: GitBranchInfo) {
    val enabled = !project.git.busy && current.hasUpstream
    val behind = current.behind > 0
    val color =
        when {
            !enabled -> IdeColors.TextSecondary.copy(alpha = DISABLED_ALPHA)
            behind -> IdeColors.GitBehind
            else -> IdeColors.TextSecondary
        }
    val tooltip =
        when {
            !current.hasUpstream -> "${current.name} tracks no remote branch"
            behind -> "Pull ${current.behind} commits into ${current.name} (fast-forward)"
            else -> "Pull ${current.name} (fast-forward)"
        }
    TooltipArea(tooltip = { Tooltip(tooltip) }) {
        Row(
            Modifier
                .height(22.dp)
                .clip(IdeShapes.Medium)
                .clickable(enabled = enabled) { project.git.syncBranch(current.name, BranchSync.Action.Pull) }
                .testTag(GitTags.QUICK_PULL)
                .padding(start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowIcon(up = false, color = color)
            Label("Pull", color = color, fontSize = 11.sp)
        }
    }
}

private const val DISABLED_ALPHA = 0.5f

// Result of the last pull or push of a chip: kept until dismissed or replaced, so an error can be read.
@Composable
private fun BranchSyncStatus(project: GitHost, sync: BranchSync) {
    val color =
        when {
            sync.isError -> IdeColors.Error
            sync.running -> IdeColors.TextSecondary
            else -> IdeColors.GitAdded
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Shrinks (ellipsis) before the dismiss button, which is measured first and must stay clickable.
        Label(
            sync.text,
            Modifier.weight(1f, fill = false).widthIn(max = 360.dp).testTag(GitTags.BRANCH_SYNC_STATUS),
            color = color,
            fontSize = 11.sp,
        )
        if (!sync.running) {
            Box(
                Modifier
                    .size(18.dp)
                    .clickable { project.git.branchSync = null }
                    .testTag(GitTags.BRANCH_SYNC_DISMISS),
                contentAlignment = Alignment.Center,
            ) {
                CloseIcon(Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun PinnedBranchChip(project: GitHost, branch: GitBranchInfo) {
    Row(
        Modifier
            .height(22.dp)
            .background(IdeColors.Editor, IdeShapes.Medium)
            .border(1.dp, IdeColors.Separator, IdeShapes.Medium)
            .clickable { project.git.checkoutBranch(branch.name) }
            .testTag(GitTags.pinnedBranch(branch.name))
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            branch.name,
            Modifier.widthIn(max = 120.dp),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
        )
        BranchSyncBadge(branch, compact = true, modifier = Modifier.padding(start = 4.dp))
        PinnedBranchMenu(project, branch)
        Box(
            Modifier
                .size(18.dp)
                .clickable { project.git.toggleBranchSelection(branch.name) }
                .testTag(GitTags.unpinnedBranch(branch.name)),
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(Modifier.size(10.dp))
        }
    }
}

// Actions of a pinned branch, without checking it out: pull (fast-forward), push, and the Git tool window.
@Composable
private fun PinnedBranchMenu(project: GitHost, branch: GitBranchInfo) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(18.dp)
                .clickable { open = true }
                .testTag(GitTags.pinnedBranchMenu(branch.name)),
            contentAlignment = Alignment.Center,
        ) {
            Chevron(expanded = true, Modifier.size(12.dp))
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }) {
            val item = @Composable { text: String, tag: String, enabled: Boolean, action: () -> Unit ->
                IdeMenuItem(
                    text = { Text(text, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        action()
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(tag),
                )
            }
            val idle = !project.git.busy
            item("Checkout", GitTags.branchMenuCheckout(branch.name), idle) { project.git.checkoutBranch(branch.name) }
            item(pullLabel(branch), GitTags.branchMenuPull(branch.name), idle && branch.hasUpstream) {
                project.git.syncBranch(branch.name, BranchSync.Action.Pull)
            }
            item(pushLabel(branch), GitTags.branchMenuPush(branch.name), idle) {
                project.git.syncBranch(branch.name, BranchSync.Action.Push)
            }
            item("Show Changes", GitTags.branchMenuChanges(branch.name), true) {
                project.showToolWindow(ToolWindow.Git)
            }
            item("Show History", GitTags.branchMenuHistory(branch.name), true) { project.showHistory(branch.name) }
            item("Unpin", GitTags.branchMenuUnpin(branch.name), true) { project.git.toggleBranchSelection(branch.name) }
        }
    }
}

private fun pullLabel(branch: GitBranchInfo): String =
    when {
        !branch.hasUpstream -> "Pull (no remote branch)"
        branch.behind > 0 -> "Pull ↓ ${branch.behind}"
        else -> "Pull"
    }

private fun pushLabel(branch: GitBranchInfo): String =
    when {
        !branch.hasUpstream -> "Push to origin/${branch.name}"
        branch.ahead > 0 -> "Push ↑ ${branch.ahead}"
        else -> "Push"
    }

/** The branch picker of the toolbar; closing it gives the focus back to the editor. */
@Composable
fun BranchPicker(project: GitHost) {
    BranchPickerPopup(project) {
        project.git.branchPickerOpen = false
        val pane = project.editor.activePane
        if (project.editor.activeTab?.document != null) {
            runCatching { pane.focusRequester.requestFocus() }
        }
    }
}
