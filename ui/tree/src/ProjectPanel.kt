package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow

@Composable
fun ProjectPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag(Tags.PROJECT_PANEL)) {
        ToolWindowHeader("Project", ToolWindow.Project, project, drag) {
            AutoSelectToggle(project)
            IconButton({
                project.tree.refresh()
                project.fileIndex.invalidate()
            }) { Label("⟳", color = IdeColors.TextSecondary) }
        }
        ProjectTree(project, Modifier.fillMaxSize())
    }
}

// Toggle of "Always Select Opened File", like the crosshair button of IntelliJ's Project view.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AutoSelectToggle(project: ProjectState) {
    val on = project.autoSelectOpenedFile
    TooltipArea(tooltip = { Tooltip("Always Select Opened File") }) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (on) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                .toggleable(value = on, onValueChange = { project.autoSelectOpenedFile = it })
                .testTag(Tags.TREE_AUTO_SELECT),
            contentAlignment = Alignment.Center,
        ) {
            LocateIcon(color = if (on) IdeColors.Text else IdeColors.TextSecondary)
        }
    }
}
