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
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.EDITOR_SERVICE
import org.example.kotlineditor.state.EditorConfigState
import org.example.kotlineditor.state.EditorConfigViolation
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.editorConfigState

private const val WINDOW_NAME = "EditorConfig"
private val HEADER_INDENT = 26.dp

/** The window this plugin contributes, named by [editorConfigToolWindow]'s defaults so the layout saves its place. */
internal val EDITORCONFIG_WINDOW = ToolWindow(WINDOW_NAME)

/** The client half of the bundled EditorConfig feature: its tool window. */
class EditorConfigClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(editorConfigToolWindow())
    }
}

/** The EditorConfig tool window: checks the open files against `.editorconfig` when it is shown. */
fun editorConfigToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(EDITORCONFIG_WINDOW, Dock.Left),
        tag = EditorConfigTags.TOOL,
        group = StripGroup.Last,
        onShown = ::checkWhenShown,
        icon = Lucide.ListFilter,
        content = { host, drag, modifier -> EditorConfigPanel(host, drag, modifier) },
    )

// Shown again: the open files may have changed meanwhile. The state is created on the first use of the window.
private fun checkWhenShown(host: ProjectHost) {
    host.scope.launch { host.editorConfigState().refresh(openPaths(host)) }
}

/** The paths (relative to the root, with `/` separators) of the documents the editors show as text. */
private fun openPaths(host: ProjectHost): List<String> {
    val tabs = host.service(EDITOR_SERVICE).allTabs
    return tabs.filterNot { it.isDiff }.map { tab -> tab.relativePath }.distinct()
}

/** The EditorConfig tool window: the violations of the open files grouped by file, opened at their line with a click. */
@Composable
fun EditorConfigPanel(project: ProjectHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val state = project.editorConfigState()
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(EditorConfigTags.PANEL)) {
        ToolWindowHeader(WINDOW_NAME, EDITORCONFIG_WINDOW, project.toolWindows, drag) {
            IconButton(
                { project.scope.launch { state.refresh(openPaths(project)) } },
                Modifier.testTag(EditorConfigTags.REFRESH),
            ) {
                RefreshIcon()
            }
            IconButton(
                { project.toolWindows.setVisible(EDITORCONFIG_WINDOW, false) },
                Modifier.testTag(EditorConfigTags.HIDE),
            ) {
                // Like the Todo window: the window is only hidden, its state is kept.
                MinimizeIcon()
            }
        }
        EditorConfigBody(project, state, Modifier.fillMaxSize())
    }
}

// Checking: a message while the first check runs; a re-check keeps the list it may refresh. A failure (the server
// unreachable) is shown as long as nothing else can be shown: a list that a later check filled stays.
@Composable
private fun EditorConfigBody(project: ProjectHost, state: EditorConfigState, modifier: Modifier = Modifier) {
    val failure = state.error
    when {
        state.violations.isNotEmpty() -> Violations(project, state.violations, modifier)
        state.loading -> {
            Message("Checking the open files…", EditorConfigTags.LOADING, IdeColors.TextSecondary, modifier)
        }
        failure != null -> {
            Message(failure, EditorConfigTags.ERROR, IdeColors.Error, modifier)
        }
        else -> {
            Message("No violation of .editorconfig in the open files", EditorConfigTags.EMPTY, IdeColors.TextSecondary, modifier)
        }
    }
}

@Composable
private fun Message(text: String, tag: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
        Label(text, Modifier.testTag(tag), color = color, fontSize = 12.sp)
    }
}

@Composable
private fun Violations(project: ProjectHost, violations: List<EditorConfigViolation>, modifier: Modifier = Modifier) {
    val byFile = violations.groupBy { violation -> violation.path }
    LazyColumn(modifier) {
        byFile.forEach { (path, fileViolations) ->
            item(key = EditorConfigTags.fileGroup(path)) { FileGroupHeader(path, fileViolations.size) }
            fileViolations.forEach { violation ->
                item(key = itemKey(violation)) { ViolationRow(project, violation) }
            }
        }
    }
}

// Two rules may be broken on one line: the key carries the rule too.
private fun itemKey(violation: EditorConfigViolation): String =
    "${violation.path}:${violation.line}:${violation.rule}"

/** Header of a file's group: its icon, its path from the project root, and how many violations it holds. */
@Composable
private fun FileGroupHeader(path: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(IdeColors.Panel)
            .testTag(EditorConfigTags.fileGroup(path))
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
private fun ViolationRow(project: ProjectHost, violation: EditorConfigViolation, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { openAt(project, violation) }
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(EditorConfigTags.item(violation.path, violation.line, violation.rule))
            .padding(start = HEADER_INDENT, top = 2.dp, bottom = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(lineLabel(violation.line), Modifier.width(36.dp), color = IdeColors.LineNumber, fontSize = 11.sp)
        Label(violation.message, Modifier.weight(1f), color = IdeColors.Text, fontSize = 12.sp)
        Label(violation.rule, color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}

// A line of 0 means the whole file: the row has no line to give.
private fun lineLabel(line: Int): String = if (line == 0) "—" else line.toString()

/** Opens [violation]'s file in the editor at its line (lines count from 1, the editor's caret from 0). */
private fun openAt(project: ProjectHost, violation: EditorConfigViolation) {
    val entry = project.entryAt(violation.path)
    val line = (violation.line - 1).coerceAtLeast(0)
    val at = TextPos(line, 0)
    project.navigate(entry, at, at)
}
