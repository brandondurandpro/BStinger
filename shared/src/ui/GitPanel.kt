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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
internal fun GitPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val changes = project.gitStatus.entries.sortedBy { it.key }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.GIT_PANEL)) {
        ToolWindowHeader("Git", ToolWindow.Git, project, drag) {
            BlameToggle(project)
            IconButton(project::refreshGitStatus, Modifier.testTag(Tags.GIT_REFRESH)) {
                Label("⟳", color = IdeColors.TextSecondary)
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Git, false) },
                Modifier.testTag(Tags.GIT_HIDE),
            ) { CloseIcon() }
        }
        if (project.isGitRepository) {
            GitActionsBar(project)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
            if (changes.isEmpty()) {
                Message("No changes", Tags.GIT_EMPTY)
            } else {
                ChangeList(project, changes)
            }
        } else {
            Message("Not a Git repository", Tags.GIT_NOT_A_REPOSITORY)
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
            .testTag(Tags.GIT_BLAME),
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

// The changed files, split into the ones checked for the commit and the others: the Commit section (with its
// checkboxes ticked) comes first when it is not empty, and the Commit button commits only its files.
@Composable
private fun ChangeList(project: ProjectState, changes: List<Map.Entry<String, GitFileStatus>>) {
    val (selected, others) = changes.partition { it.key in project.selectedForCommit }
    LazyColumn(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            item(key = "header:commit") { SectionHeader("Commit (${selected.size})", Tags.GIT_COMMIT_FILES) }
            items(selected, key = { it.key }) { (path, status) ->
                ChangeRow(project, path, status, checked = true)
            }
        }
        if (others.isNotEmpty()) {
            item(key = "header:other") { SectionHeader("Other changes (${others.size})", Tags.GIT_OTHER_FILES) }
            items(others, key = { it.key }) { (path, status) ->
                ChangeRow(project, path, status, checked = false)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(IdeColors.Panel)
            .testTag(tag)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChangeRow(project: ProjectState, path: String, status: GitFileStatus, checked: Boolean) {
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        // The checkbox decides where this row sits (Commit or Other changes); the file name opens its diff.
        BranchCheckbox(
            checked,
            Modifier.padding(start = 8.dp).testTag(ItemTags.gitChangeCheck(path)),
        ) { project.toggleCommitSelection(path) }
        Row(
            Modifier
                .weight(1f)
                .clickable { project.openDiff(path) }
                .testTag(ItemTags.gitChange(path))
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
