package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.FileDialog
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TreeRow
import org.example.kotlineditor.state.relativePath

internal val ROW_HEIGHT = 24.dp
private val INDENT = 18.dp
private val CHEVRON_SIZE = 16.dp
private val ROW_START = 8.dp

/** What a tree row does when pressed: primary button (on the chevron or not, with the time), secondary button. */
internal class RowActions(
    val onPress: (onChevron: Boolean, uptimeMillis: Long) -> Unit,
    val onContextMenu: (Offset) -> Unit,
)

@Composable
internal fun TreeRowView(
    row: TreeRow,
    relativePath: String,
    gitStatus: GitFileStatus?,
    focusedSelection: Boolean,
    selected: Boolean,
    actions: RowActions,
    menu: @Composable () -> Unit,
) {
    val background =
        when {
            focusedSelection -> IdeColors.Selection
            selected -> IdeColors.SelectionInactive
            else -> Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(background)
            .testTag(Tags.treeRow(relativePath))
            .rowPresses(row, actions)
            .padding(start = ROW_START + INDENT * row.depth, end = ROW_START),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.entry.isDirectory) {
            Chevron(row.expanded, Modifier.testTag(Tags.treeChevron(relativePath)))
        } else {
            Spacer(Modifier.width(CHEVRON_SIZE))
        }
        FileIcon(row.entry.name, row.entry.isDirectory, Modifier.padding(start = 2.dp))
        val statusTag = gitStatus?.let { Modifier.testTag(Tags.treeGitStatus(relativePath, it.name)) } ?: Modifier
        Label(
            row.entry.name,
            Modifier.padding(start = 6.dp).then(statusTag),
            color = gitStatus?.let(::gitColor) ?: IdeColors.Text,
        )
        if (row.loading) Label("  loading…", color = IdeColors.TextSecondary)
        menu()
    }
}

// Raw press events: awaitFirstDown ignores the secondary button, which opens the context menu.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.rowPresses(row: TreeRow, actions: RowActions) =
    pointerInput(row.entry.path) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull()
                if (event.type == PointerEventType.Press && change != null) onRowPress(row, actions, event, change)
            }
        }
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun PointerInputScope.onRowPress(
    row: TreeRow,
    actions: RowActions,
    event: PointerEvent,
    change: PointerInputChange,
) {
    if (event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed) {
        actions.onContextMenu(change.position)
    } else {
        val chevronStart = (ROW_START + INDENT * row.depth).toPx()
        val onChevron = row.entry.isDirectory && change.position.x in chevronStart..chevronStart + CHEVRON_SIZE.toPx()
        actions.onPress(onChevron, change.uptimeMillis)
    }
}

/** IntelliJ's VCS file status colors. */
fun gitColor(status: GitFileStatus): Color =
    when (status) {
        GitFileStatus.Modified, GitFileStatus.Renamed -> IdeColors.Modified
        GitFileStatus.Added -> IdeColors.GitAdded
        GitFileStatus.Untracked -> IdeColors.GitUntracked
        GitFileStatus.Deleted -> IdeColors.TextSecondary
    }

@Composable
internal fun TreeContextMenu(project: ProjectState, entry: FileEntry, offset: DpOffset, onDismiss: () -> Unit) {
    val tree = project.tree
    val directory = if (entry.isDirectory) entry else tree.parentOf(entry) ?: tree.root
    val isRoot = entry.path == tree.root.path
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.testTag(Tags.TREE_MENU),
    ) {
        MenuItem(
            "New File…",
            Tags.MENU_NEW_FILE,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = false) } }
        MenuItem(
            "New Directory…",
            Tags.MENU_NEW_DIRECTORY,
        ) { action { project.fileDialog = FileDialog.Create(directory, isDirectory = true) } }
        if (!isRoot) {
            MenuItem("Rename…", Tags.MENU_RENAME) { action { project.fileDialog = FileDialog.Rename(entry) } }
            MenuItem("Delete…", Tags.MENU_DELETE) { action { project.fileDialog = FileDialog.Delete(entry) } }
        }
        MenuItem("Copy Path", Tags.MENU_COPY_PATH) { action { TextClipboard.set(entry.path) } }
        MenuItem("Reload from Disk", Tags.MENU_RELOAD) { action { tree.refresh() } }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 13.sp) },
        onClick = onClick,
        modifier = Modifier.height(30.dp).testTag(tag),
    )
}
