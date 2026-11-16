package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow

// The left third: the project, the remote it runs on, its branch, and the button of the left column.
@Composable
internal fun ToolbarStart(app: AppState, project: ProjectState, modifier: Modifier) {
    Layout(
        content = {
            Box(Modifier.layoutId(StartSlot.Project)) { ProjectWidget(app, project) }
            if (app.session.isRemote) {
                Box(Modifier.layoutId(StartSlot.Remote)) { RemoteBadge(app) }
            }
            if (project.git.currentBranch != null) {
                BranchWidget(project, Modifier.layoutId(StartSlot.Branch).padding(start = 12.dp))
            }
            Box(Modifier.layoutId(StartSlot.LeftColumn).padding(start = 8.dp)) { LeftColumnButton(project) }
        },
        modifier = modifier,
        measurePolicy = ToolbarStartPolicy,
    )
}

private enum class StartSlot { Project, Remote, Branch, LeftColumn }

// A long project name is cut down to this width to leave the branch its room.
private val PROJECT_WIDGET_MIN_WIDTH = 110.dp

// When the section is too narrow for everything, the project name gives way first (down to
// [PROJECT_WIDGET_MIN_WIDTH]), so that the branch keeps its name and its Pull button; the remote badge and the button
// of the left column keep their size.
private object ToolbarStartPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val remote = measurables.firstOrNull { it.layoutId == StartSlot.Remote }?.measure(loose)
        val leftColumn = measurables.firstOrNull { it.layoutId == StartSlot.LeftColumn }?.measure(loose)
        val fixedWidth = (remote?.width ?: 0) + (leftColumn?.width ?: 0)
        val available = (constraints.maxWidth - fixedWidth).coerceAtLeast(0)

        val projectMeasurable = measurables.firstOrNull { it.layoutId == StartSlot.Project }
        val branchMeasurable = measurables.firstOrNull { it.layoutId == StartSlot.Branch }
        val projectWanted = projectMeasurable?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        // The least the branch needs: its name and its Pull button, not the result of the last pull.
        val branchNeeded = branchMeasurable?.minIntrinsicWidth(constraints.maxHeight) ?: 0
        val projectFloor = minOf(projectWanted, PROJECT_WIDGET_MIN_WIDTH.roundToPx())
        val projectWidth = (available - branchNeeded).coerceIn(projectFloor, projectWanted)
        val project = projectMeasurable?.measure(loose.copy(maxWidth = minOf(projectWidth, available)))
        val branchMaxWidth = (available - (project?.width ?: 0)).coerceAtLeast(0)
        val branch = branchMeasurable?.measure(loose.copy(maxWidth = branchMaxWidth))

        val placed = listOfNotNull(project, remote, branch, leftColumn)
        val height = constraints.constrainHeight(placed.maxOfOrNull { it.height } ?: 0)
        return layout(constraints.maxWidth, height) {
            var x = 0
            placed.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

// The right third: what runs and ships, then, at the far right, the Agent button that opens the agent window.
@Composable
internal fun ToolbarEnd(app: AppState, project: ProjectState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        RunWidget(project.run, { project.showToolWindow(ToolWindow.Run) })
        Spacer(Modifier.width(10.dp))
        IdeButton("Deploy", { project.deployments.show() }, Modifier.testTag(AppTags.DEPLOY_BUTTON))
        if (app.settings.agentModeEnabled) {
            Spacer(Modifier.width(14.dp))
            AgentButton(project.agentMode) { project.openAgentWindow() }
        }
    }
}

// The gear at the bottom of the strip: opens the settings, like Ctrl+Alt+S.
@Composable
internal fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(IdeShapes.Large)
            .clickable(onClick = onClick)
            .testTag(AppTags.STRIP_SETTINGS),
        contentAlignment = Alignment.Center,
    ) {
        IdeIcon(Lucide.Settings)
    }
}

// Hides or shows the left column (Project, Git) of the IDE; the windows in it keep their own visibility.
@Composable
private fun LeftColumnButton(project: ProjectState) {
    // Lit (accent icon on a selected background) while the column is shown, plain while it is hidden.
    val shown = !project.toolWindows.leftColumnHidden
    val tint = if (shown) IdeColors.Accent else IdeColors.TextSecondary
    val background = if (shown) IdeColors.Selection else Color.Transparent
    Box(
        Modifier
            .size(30.dp)
            .clip(IdeShapes.Large)
            .background(background)
            .clickable { project.toolWindows.toggleLeftColumn() }
            .testTag(AppTags.TOOLBAR_LEFT_COLUMN),
        contentAlignment = Alignment.Center,
    ) {
        IdeIcon(Lucide.PanelLeft, color = tint)
    }
}
