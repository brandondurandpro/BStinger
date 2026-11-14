package org.example.kotlineditor.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.workspaceProject

// The left third: the project, the remote it runs on, and its branch.
@Composable
internal fun ToolbarStart(app: AppState, project: ProjectState, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProjectWidget(app, project)
        if (app.session.isRemote) {
            RemoteBadge(app)
        }
        // In agent mode, the branch of the project the agent of the active conversation works on.
        val branchProject = if (project.agentMode) project.agent.current?.workspaceProject else project
        if (branchProject != null && branchProject.git.currentBranch != null) {
            BranchWidget(branchProject, Modifier.weight(1f).padding(start = 12.dp))
        }
    }
}

// The right third: what runs and ships, then, at the far right, the switch between the IDE and the agent mode.
@Composable
internal fun ToolbarEnd(app: AppState, project: ProjectState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        RunWidget(project.run, { project.showToolWindow(ToolWindow.Run) })
        Spacer(Modifier.width(10.dp))
        IdeButton("Deploy", { project.deployments.show() }, Modifier.testTag(AppTags.DEPLOY_BUTTON))
        if (app.settings.agentModeEnabled) {
            Spacer(Modifier.width(14.dp))
            ModeSwitch(project.agentMode) { project.agentMode = it }
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
