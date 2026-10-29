package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.openDiff

/** Git tool window: changed files below the project tree, a click opens the diff of the file. */
@Composable
internal fun GitPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val changes = project.gitStatus.entries.sortedBy { it.key }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.GIT_PANEL)) {
        ToolWindowHeader("Git", ToolWindow.Git, project, drag) {
            IconButton(project::refreshGitStatus, Modifier.testTag(Tags.GIT_REFRESH)) {
                Label("⟳", color = IdeColors.TextSecondary)
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Git, false) },
                Modifier.testTag(Tags.GIT_HIDE),
            ) { CloseIcon() }
        }
        when {
            !project.isGitRepository -> Message("Not a Git repository", Tags.GIT_NOT_A_REPOSITORY)
            changes.isEmpty() -> Message("No changes", Tags.GIT_EMPTY)
            else -> ChangeList(project, changes)
        }
    }
}

@Composable
private fun Message(text: String, tag: String) {
    Box(Modifier.fillMaxSize().padding(12.dp).testTag(tag)) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ChangeList(project: ProjectState, changes: List<Map.Entry<String, GitFileStatus>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(changes, key = { it.key }) { (path, status) ->
            ChangeRow(project, path, status)
        }
    }
}

@Composable
private fun ChangeRow(project: ProjectState, path: String, status: GitFileStatus) {
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable { project.openDiff(path) }
            .testTag(ItemTags.gitChange(path))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            vcsStatusLetter(status),
            Modifier.width(16.dp),
            color = gitColor(status),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        FileIcon(name, isDirectory = false)
        Label(name, Modifier.padding(start = 6.dp), color = gitColor(status))
        if (directory.isNotEmpty()) {
            Label(
                directory,
                Modifier.weight(1f).padding(start = 6.dp),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

private fun vcsStatusLetter(status: GitFileStatus): String =
    when (status) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Untracked -> "U"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
    }
