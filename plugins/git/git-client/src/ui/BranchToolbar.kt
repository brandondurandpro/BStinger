package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutIdParentData
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.state.BranchSync
import org.example.kotlineditor.state.GitHost

// The branch widget of the toolbar: the current branch (opens the branch picker), its Pull button, and the result of
// the last pull. The pinned branches are not listed there: the toolbar has no room for them.

@Composable
fun BranchWidget(project: GitHost, modifier: Modifier = Modifier) {
    val current = project.git.branchInfos.firstOrNull { it.current }
    Layout(
        content = {
            BranchChip(project, current, Modifier.layoutId(BranchSlot.Chip))
            current?.let { Box(Modifier.layoutId(BranchSlot.Pull)) { QuickPullButton(project, it) } }
            project.git.branchSync?.let { Box(Modifier.layoutId(BranchSlot.Status)) { BranchSyncStatus(project, it) } }
        },
        modifier = modifier,
        measurePolicy = BranchWidgetPolicy,
    )
}

private enum class BranchSlot { Chip, Pull, Status }

private val BRANCH_SLOT_GAP = 6.dp

private fun IntrinsicMeasurable.branchSlot(): Any? = (parentData as? LayoutIdParentData)?.layoutId

// What is left of [max] once [used] is taken; an unbounded width stays unbounded.
private fun remainingWidth(max: Int, used: Int): Int {
    if (max == Constraints.Infinity) return max
    return (max - used).coerceAtLeast(0)
}

// A narrow toolbar keeps the Pull button whole: it is measured first, then the chip (whose branch name ends in an
// ellipsis), and the result of the last pull takes what is left. The least width the widget asks for (its minimum
// intrinsic width) is the chip and the button, so that a long result never pushes the rest of the toolbar away.
private object BranchWidgetPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = BRANCH_SLOT_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val pull = measurables.firstOrNull { it.branchSlot() == BranchSlot.Pull }?.measure(loose)
        val pullWidth = if (pull == null) 0 else pull.width + gap
        val chipMaxWidth = remainingWidth(constraints.maxWidth, pullWidth)
        val chipMeasurable = measurables.firstOrNull { it.branchSlot() == BranchSlot.Chip }
        val chip = chipMeasurable?.measure(loose.copy(maxWidth = chipMaxWidth))
        val chipWidth = if (chip == null) 0 else chip.width + gap
        val statusMaxWidth = remainingWidth(chipMaxWidth, chipWidth)
        val statusMeasurable = measurables.firstOrNull { it.branchSlot() == BranchSlot.Status }
        val status = statusMeasurable?.measure(loose.copy(maxWidth = statusMaxWidth))
        val placed = listOfNotNull(chip, pull, status)
        val gaps = gap * (placed.size - 1).coerceAtLeast(0)
        val width = constraints.constrainWidth(placed.sumOf { it.width } + gaps)
        val height = constraints.constrainHeight(placed.maxOfOrNull { it.height } ?: 0)
        return layout(width, height) {
            var x = 0
            placed.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val essential = measurables.filter { it.branchSlot() != BranchSlot.Status }
        val gaps = BRANCH_SLOT_GAP.roundToPx() * (essential.size - 1).coerceAtLeast(0)
        return essential.sumOf { it.maxIntrinsicWidth(height) } + gaps
    }
}

// The current branch, its commits to pull and to push; a click opens the branch picker.
@Composable
private fun BranchChip(project: GitHost, current: GitBranchInfo?, modifier: Modifier) {
    val background = if (project.git.branchPickerOpen) IdeColors.Selection else Color.Transparent
    Row(
        modifier
            .background(background, IdeShapes.Medium)
            .clickable { project.git.branchPickerOpen = true }
            .testTag(GitTags.GIT_BRANCH)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchIcon(color = if (project.git.branchPickerOpen) IdeColors.Accent else IdeColors.TextSecondary)
        // Shrinks (ellipsis) before the counts and the chevron.
        Label(
            project.git.currentBranch.orEmpty(),
            Modifier.weight(1f, fill = false).padding(start = 5.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        current?.let { BranchSyncBadge(it, compact = true, modifier = Modifier.padding(start = 6.dp)) }
        Chevron(expanded = false, Modifier.padding(start = 2.dp))
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
