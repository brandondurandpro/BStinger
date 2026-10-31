package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.checkoutBranch
import org.example.kotlineditor.state.commitChanges
import org.example.kotlineditor.state.pull
import org.example.kotlineditor.state.push

/** Commit, branch switch, pull and push, at the top of the Git panel (IntelliJ's commit area). */
@Composable
internal fun GitActionsBar(project: ProjectState) {
    Column(Modifier.fillMaxWidth().background(IdeColors.Panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BranchMenu(project)
            Spacer(Modifier.weight(1f))
            IdeButton("Pull", { project.pull() }, Modifier.testTag(Tags.GIT_PULL), enabled = !project.gitBusy)
            IdeButton(
                "Push",
                { project.push() },
                Modifier.padding(start = 6.dp).testTag(Tags.GIT_PUSH),
                enabled = !project.gitBusy,
            )
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IdeTextField(
                project.commitMessage,
                { project.commitMessage = it },
                Modifier.weight(1f).testTag(Tags.GIT_COMMIT_MESSAGE),
                placeholder = "Commit message",
            )
            IdeButton(
                "Commit",
                { project.commitChanges(project.commitMessage) },
                Modifier.padding(start = 6.dp).testTag(Tags.GIT_COMMIT),
                primary = true,
                enabled = !project.gitBusy && project.selectedForCommit.isNotEmpty(),
            )
        }
        project.gitMessage?.let { message ->
            Label(
                message,
                Modifier.padding(top = 6.dp).testTag(Tags.GIT_MESSAGE),
                color = if (project.gitMessageIsError) IdeColors.Error else IdeColors.GitAdded,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun BranchMenu(project: ProjectState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable(enabled = !project.gitBusy && project.gitBranches.isNotEmpty()) { open = true }
                .testTag(Tags.GIT_BRANCH_BUTTON)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchIcon()
            Label(project.gitBranch ?: "No branch", Modifier.padding(start = 4.dp), fontSize = 12.sp)
            Chevron(expanded = false)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            project.gitBranches.forEach { branch ->
                DropdownMenuItem(
                    text = { Text(branch, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        project.checkoutBranch(branch)
                    },
                    modifier = Modifier.testTag(ItemTags.gitBranch(branch)),
                )
            }
        }
    }
}
