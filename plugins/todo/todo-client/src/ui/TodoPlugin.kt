package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.state.TodoItem
import org.example.kotlineditor.state.TodoState
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.todoState

private const val WINDOW_NAME = "Todo"
private val HEADER_INDENT = 26.dp

/** The window this plugin contributes, named by [todoToolWindow]'s defaults so the layout saves its place. */
internal val TODO_WINDOW = ToolWindow(WINDOW_NAME)

/** The Todo tool window (the TODO and FIXME markers of the project): scans the project when it is shown. */
fun todoToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(TODO_WINDOW, Dock.Left),
        tag = TodoTags.TOOL_TODO,
        group = StripGroup.Last,
        onShown = ::scanWhenShown,
        icon = Lucide.CircleCheck,
        content = { host, drag, modifier -> TodoPanel(host, drag, modifier) },
    )

// Shown again: the markers may have changed meanwhile. The state is created on the first use of the window.
private fun scanWhenShown(host: ProjectHost) {
    val todo = host.todoState()
    host.scope.launch { todo.refresh() }
}

/** The Todo tool window: the markers of the project grouped by file, opened at their line with a click. */
@Composable
fun TodoPanel(project: ProjectHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val todo = project.todoState()
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(TodoTags.PANEL)) {
        ToolWindowHeader(WINDOW_NAME, TODO_WINDOW, project.toolWindows, drag) {
            IconButton(
                { project.scope.launch { todo.refresh() } },
                Modifier.testTag(TodoTags.REFRESH),
            ) {
                RefreshIcon()
            }
            IconButton(
                { project.toolWindows.setVisible(TODO_WINDOW, false) },
                Modifier.testTag(TodoTags.HIDE),
            ) {
                // Like the terminal: the window is only hidden, its state is kept.
                MinimizeIcon()
            }
        }
        TodoBody(project, todo, Modifier.fillMaxSize())
    }
}

// Scanning: a message while the first scan runs; a re-scan keeps the list it may refresh. A failure (the server
// unreachable) is shown as long as nothing else can be shown: a list that a later scan filled stays.
@Composable
private fun TodoBody(project: ProjectHost, todo: TodoState, modifier: Modifier = Modifier) {
    val failure = todo.error
    when {
        todo.items.isNotEmpty() -> TodoItems(project, todo.items, modifier)
        todo.loading -> TodoMessage("Scanning…", TodoTags.LOADING, IdeColors.TextSecondary, modifier)
        failure != null -> TodoMessage(failure, TodoTags.ERROR, IdeColors.Error, modifier)
        else -> TodoMessage("No TODO or FIXME marker in the project", TodoTags.EMPTY, IdeColors.TextSecondary, modifier)
    }
}

@Composable
private fun TodoMessage(text: String, tag: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
        Label(text, Modifier.testTag(tag), color = color, fontSize = 12.sp)
    }
}

@Composable
private fun TodoItems(project: ProjectHost, items: List<TodoItem>, modifier: Modifier = Modifier) {
    val byFile = items.groupBy { marker -> marker.path }
    LazyColumn(modifier) {
        byFile.forEach { (path, markers) ->
            item(key = TodoTags.fileGroup(path)) { FileGroupHeader(path, markers.size) }
            markers.forEach { marker ->
                item(key = "${marker.path}:${marker.line}") { TodoItemRow(project, marker) }
            }
        }
    }
}

/** Header of a file's group: its icon, its path from the project root, and how many markers it holds. */
@Composable
private fun FileGroupHeader(path: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(IdeColors.Panel)
            .testTag(TodoTags.fileGroup(path))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(path.substringAfterLast('/'), isDirectory = false)
        Label(
            path,
            Modifier.padding(start = 6.dp).weight(1f),
            color = IdeColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Label(count.toString(), color = IdeColors.LineNumber, fontSize = 11.sp)
    }
}

@Composable
private fun TodoItemRow(project: ProjectHost, marker: TodoItem, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { openAt(project, marker) }
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(TodoTags.item(marker.path, marker.line))
            .padding(start = HEADER_INDENT, top = 2.dp, bottom = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(marker.line.toString(), Modifier.width(36.dp), color = IdeColors.LineNumber, fontSize = 11.sp)
        Label(marker.text, Modifier.weight(1f), color = IdeColors.Text, fontSize = 12.sp)
    }
}

/** Opens [marker]'s file in the editor at its line (lines count from 1, the editor's caret from 0). */
private fun openAt(project: ProjectHost, marker: TodoItem) {
    val entry = project.entryAt(marker.path)
    val at = TextPos(marker.line - 1, 0)
    project.navigate(entry, at, at)
}
