package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.GitHost
import org.example.kotlineditor.state.ToolWindow

/** Git tool window: branch and commit actions, then the changed files split into the checked ones and the others. */
@Composable
fun GitPanel(project: GitHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val changes =
        project.git.status.entries
            .sortedBy { it.key }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(GitTags.GIT_PANEL)) {
        ToolWindowHeader("Git", ToolWindow.Git, project.toolWindows, drag) {
            BlameToggle(project)
            IconButton(project.git::refreshStatus, Modifier.testTag(GitTags.GIT_REFRESH)) {
                RefreshIcon()
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Git, false) },
                Modifier.testTag(GitTags.GIT_HIDE),
            ) { CloseIcon() }
        }
        if (project.git.isRepository) {
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
private fun BlameToggle(project: GitHost) {
    val active = project.editor.activeTab?.blameVisible == true
    val enabled = project.editor.activeTab?.document != null
    Box(
        Modifier
            .padding(end = 2.dp)
            .background(if (active) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
            .clickable(enabled = enabled) { project.git.toggleBlame() }
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
private fun SelectAll(project: GitHost, changes: Int) {
    val checked = project.git.selectedForCommit.size
    val allChecked = checked == changes && changes > 0
    val someChecked = checked in 1 until changes
    Row(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel)
            .padding(start = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .testTag(GitTags.GIT_SELECT_ALL)
                .clickable(
                    remember { MutableInteractionSource() },
                    indication = null,
                ) { project.git.toggleAllCommitSelection() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (allChecked) IdeColors.Accent else Color.Transparent, IdeShapes.Small)
                    .border(
                        1.dp,
                        if (allChecked || someChecked) IdeColors.Accent else IdeColors.InputBorder,
                        IdeShapes.Small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    allChecked -> {
                        CheckIcon(Modifier.size(9.dp), color = IdeColors.OnAccent)
                    }
    
                    someChecked -> {
                        Box(
                            Modifier
                                .size(width = 8.dp, height = 2.dp)
                                .background(IdeColors.Accent, RoundedCornerShape(1.dp)),
                        )
                    }
                }
            }
            Label("Changes ($changes)", Modifier.padding(start = 8.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        Box(Modifier.weight(1f))
        val changedFiles = project.git.status.keys.sorted()
        Label(
            "Review manually",
            Modifier
                .clickable { project.openManualReview(changedFiles) }
                .testTag(GitTags.GIT_REVIEW_MANUAL)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        val checkedFiles = project.git.selectedForCommit
        if (checkedFiles.isNotEmpty()) {
            ConfirmButton("Revert checked", GitTags.GIT_REVERT_CHECKED, enabled = !project.git.busy) {
                project.git.revert(checkedFiles)
            }
        }
    }
}

// One list of the changed files with a checkbox per file: the checked ones are the files of the next commit, and
// a click on the file name opens its diff. The checked state is read per row, so a tick recomposes only that row.
@Composable
private fun ChangeList(project: GitHost, changes: List<Map.Entry<String, GitFileStatus>>) {
    val ordered = changes.map { it.key }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state) {
            items(changes, key = { it.key }) { (path, status) ->
                val checked = path in project.git.selectedForCommit
                ChangeRow(project, path, status, checked, picked = path in project.git.pickedChanges, ordered)
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(GitTags.GIT_SCROLLBAR),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChangeRow(
    project: GitHost,
    path: String,
    status: GitFileStatus,
    checked: Boolean,
    picked: Boolean,
    ordered: List<String>,
) {
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    var menu by remember { mutableStateOf(false) }
    // A click highlights the row (Ctrl adds or removes it, Shift extends to it) and, alone, shows its diff; a right
    // click opens the menu on the highlighted rows.
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            pressRow(project, path, ordered, event) { menu = true }
        }
    val background = if (picked) IdeColors.Selection else Color.Transparent
    // The border of an unchecked box has the colour of the highlight, so on a highlighted row it takes a stronger one.
    val uncheckedBorder = if (picked) IdeColors.TextSecondary else IdeColors.InputBorder
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The checkbox decides what the next commit carries; the file name opens the diff.
        BranchCheckbox(
            checked,
            Modifier.padding(start = 9.dp).testTag(GitTags.gitChangeCheck(path)),
            size = 14.dp,
            uncheckedBorder = uncheckedBorder,
        ) { project.git.toggleCommitSelection(path) }
        Row(
            Modifier
                .weight(1f)
                .then(press)
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
        ConfirmButton("Revert", GitTags.gitChangeRevert(path), enabled = !project.git.busy) {
            project.git.revert(listOf(path))
        }
        if (menu) ChangesMenu(project) { menu = false }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun pressRow(project: GitHost, path: String, ordered: List<String>, event: PointerEvent, openMenu: () -> Unit) {
    val modifiers = event.keyboardModifiers
    val ctrl = modifiers.isCtrlPressed || modifiers.isMetaPressed
    val shift = modifiers.isShiftPressed
    when {
        event.button == PointerButton.Secondary -> {
            project.git.pickChangeForMenu(path)
            openMenu()
        }

        event.buttons.isPrimaryPressed -> {
            project.git.pickChange(path, ordered, toggle = ctrl, extend = shift)
            if (!ctrl && !shift) openChange(project, path)
        }
    }
}

// The menu of a right click: reverts every highlighted row at once; the item asks again before it does.
@Composable
private fun ChangesMenu(project: GitHost, onClose: () -> Unit) {
    val rows = project.git.pickedChanges
    var confirming by remember { mutableStateOf(false) }
    val noun = if (rows.size == 1) "file" else "files"
    IdeDropdownMenu(expanded = true, onDismissRequest = onClose) {
        IdeMenuItem(
            text = {
                val text = if (confirming) "Sure? Revert ${rows.size} $noun" else "Revert ${rows.size} $noun"
                Text(text, fontSize = 13.sp, color = if (confirming) IdeColors.Error else IdeColors.Text)
            },
            enabled = rows.isNotEmpty() && !project.git.busy,
            onClick = {
                if (confirming) {
                    onClose()
                    project.git.revert(rows)
                } else {
                    confirming = true
                }
            },
            modifier = Modifier.testTag(GitTags.GIT_REVERT_PICKED),
        )
    }
}

// A conflicted file opens its three-way merge, any other change its diff.
private fun openChange(project: GitHost, path: String) {
    val conflicted = project.git.status[path] == GitFileStatus.Conflicted
    if (conflicted) project.openMerge(path) else project.openDiff(path)
}

private fun vcsStatusLetter(status: GitFileStatus): String =
    when (status) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Untracked -> "U"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
        GitFileStatus.Conflicted -> "C"
    }
