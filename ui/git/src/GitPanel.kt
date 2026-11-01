package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.openDiff
import org.example.kotlineditor.state.toggleBlame

/** Git tool window: branch and commit actions, then the changed files split into the checked ones and the others. */
@Composable
fun GitPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val changes = project.gitStatus.entries.sortedBy { it.key }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(GitTags.GIT_PANEL)) {
        ToolWindowHeader("Git", ToolWindow.Git, project, drag) {
            BlameToggle(project)
            IconButton(project::refreshGitStatus, Modifier.testTag(GitTags.GIT_REFRESH)) {
                Label("⟳", color = IdeColors.TextSecondary)
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Git, false) },
                Modifier.testTag(GitTags.GIT_HIDE),
            ) { CloseIcon() }
        }
        if (project.isGitRepository) {
            GitActionsBar(project)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
            if (changes.isEmpty()) {
                Message("No changes", GitTags.GIT_EMPTY)
            } else {
                SelectAll(project, changes.size)
                ChangeList(project, changes)
            }
        } else {
            Message("Not a Git repository", GitTags.GIT_NOT_A_REPOSITORY)
        }
    }
}

// Toggles the blame annotations of the active editor, like "Annotate" in IntelliJ's gutter menu.
@Composable
private fun BlameToggle(project: ProjectState) {
    val active = project.editor.activeTab?.blameVisible == true
    val enabled = project.editor.activeTab?.document != null
    Box(
        Modifier
            .padding(end = 2.dp)
            .background(if (active) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { project.toggleBlame() }
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .testTag(GitTags.GIT_BLAME),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            "Annotate",
            color = if (enabled) IdeColors.TextSecondary else IdeColors.LineNumber,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Message(text: String, tag: String) {
    Box(Modifier.fillMaxSize().padding(12.dp).testTag(tag)) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

// Master checkbox above the list: it ticks every change for the commit, or unticks them all. A dash marks the
// partial selection of IntelliJ's checkboxes, and its label tells how many changes are listed.
@Composable
private fun SelectAll(project: ProjectState, changes: Int) {
    val checked = project.selectedForCommit.size
    val allChecked = checked == changes && changes > 0
    val someChecked = checked in 1 until changes
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(GitTags.GIT_SELECT_ALL)
            .clickable(
                remember { MutableInteractionSource() },
                indication = null,
            ) { project.toggleAllCommitSelection() }
            .background(IdeColors.Panel)
            .padding(start = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (allChecked) IdeColors.Accent else Color.Transparent, RoundedCornerShape(4.dp))
                .border(
                    1.dp,
                    if (allChecked || someChecked) IdeColors.Accent else IdeColors.InputBorder,
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                allChecked -> {
                    CheckIcon(Modifier.size(9.dp), color = Color.White)
                }

                someChecked -> {
                    Box(
                        Modifier
                            .size(width = 8.dp, height = 2.dp)
                            .background(Color.White, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
        Label("Changes ($changes)", Modifier.padding(start = 8.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}

// One list of the changed files with a checkbox per file: the checked ones are the files of the next commit, and
// a click on the file name opens its diff. The checked state is read per row, so a tick recomposes only that row.
@Composable
private fun ChangeList(project: ProjectState, changes: List<Map.Entry<String, GitFileStatus>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(changes, key = { it.key }) { (path, status) ->
            ChangeRow(project, path, status, checked = path in project.selectedForCommit)
        }
    }
}

@Composable
private fun ChangeRow(project: ProjectState, path: String, status: GitFileStatus, checked: Boolean) {
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        // The checkbox decides what the next commit carries; the file name opens the diff.
        BranchCheckbox(
            checked,
            Modifier.padding(start = 9.dp).testTag(GitTags.gitChangeCheck(path)),
            size = 14.dp,
        ) { project.toggleCommitSelection(path) }
        Row(
            Modifier
                .weight(1f)
                .clickable { project.openDiff(path) }
                .testTag(GitTags.gitChange(path))
                .padding(horizontal = 6.dp),
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
}

private fun vcsStatusLetter(status: GitFileStatus): String =
    when (status) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Untracked -> "U"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
    }
