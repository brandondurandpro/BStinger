package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow

/**
 * Fixed left strip: one button per tool window, wherever the panel itself is docked; [top] adds buttons above them
 * (the conversation list of agent mode).
 */
@Composable
internal fun ToolStrip(project: ProjectState?, devtools: Boolean, top: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.width(STRIP_WIDTH).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        top()
        if (project != null) ToolButtons(project, devtools)
    }
}

@Composable
private fun ColumnScope.ToolButtons(project: ProjectState, devtools: Boolean) {
    ToolButton(project, ToolWindow.Project, Tags.TOOL_PROJECT) { FolderIcon(Modifier.size(18.dp)) }
    ToolButton(project, ToolWindow.Git, Tags.TOOL_GIT) { BranchIcon(color = IdeColors.Text) }
    ToolButton(project, ToolWindow.Branches, Tags.TOOL_BRANCHES) { HistoryIcon(color = IdeColors.Text) }
    Spacer(Modifier.weight(1f))
    // Bottom tool windows sit at the bottom of the strip, like in IntelliJ's New UI.
    if (devtools) {
        ToolButton(project, ToolWindow.Network, Tags.TOOL_NETWORK) {
            Label("⇅", color = IdeColors.Text, fontWeight = FontWeight.Bold)
        }
    }
    ToolButton(project, ToolWindow.LanguageServers, Tags.TOOL_LANGUAGE_SERVERS) {
        Label("LS", color = IdeColors.Text, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
    ToolButton(project, ToolWindow.Terminal, Tags.TOOL_TERMINAL) {
        Label(">_", color = IdeColors.Text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToolButton(project: ProjectState, window: ToolWindow, tag: String, icon: @Composable () -> Unit) {
    val active = project.toolWindows.isVisible(window)
    Box(
        Modifier
            .size(30.dp)
            .background(if (active) IdeColors.Selection else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { project.toggleToolWindow(window) }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** A dock, with its splitter and its visible windows; nothing is drawn when it is empty. */
@Composable
internal fun ToolDock(project: ProjectState, dock: Dock, drag: ToolWindowDrag) {
    val windows = project.toolWindows.visibleIn(dock)
    if (windows.isEmpty()) return
    when (dock) {
        Dock.Left -> {
            Row(Modifier.fillMaxHeight()) {
                StackedWindows(project, dock, windows, drag, Modifier.width(project.toolWindows.sizeOf(dock).dp))
                dockResizeSplitter(project, dock)
            }
        }

        Dock.Right -> {
            Row(Modifier.fillMaxHeight()) {
                dockResizeSplitter(project, dock)
                StackedWindows(project, dock, windows, drag, Modifier.width(project.toolWindows.sizeOf(dock).dp))
            }
        }

        Dock.Bottom -> {
            Column(Modifier.fillMaxWidth()) {
                dockResizeSplitter(project, dock)
                StackedWindows(
                    project,
                    dock,
                    windows,
                    drag,
                    Modifier.fillMaxWidth().height(project.toolWindows.sizeOf(dock).dp),
                )
            }
        }
    }
}

// Windows share their dock: stacked vertically on the sides, side by side at the bottom.
@Composable
private fun StackedWindows(
    project: ProjectState,
    dock: Dock,
    windows: List<ToolWindow>,
    drag: ToolWindowDrag,
    modifier: Modifier,
) {
    if (dock == Dock.Bottom) {
        Row(modifier.fillMaxSize()) {
            windows.forEachIndexed { index, window ->
                if (index > 0) stackedWindowSplitter(project, dock, windows[index - 1], window)
                ToolWindowContent(project, window, drag, Modifier.weight(project.toolWindows.weightOf(window)))
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            windows.forEachIndexed { index, window ->
                if (index > 0) stackedWindowSplitter(project, dock, windows[index - 1], window)
                ToolWindowContent(project, window, drag, Modifier.weight(project.toolWindows.weightOf(window)))
            }
        }
    }
}

@Composable
private fun ToolWindowContent(project: ProjectState, window: ToolWindow, drag: ToolWindowDrag, modifier: Modifier) {
    when (window) {
        ToolWindow.Project -> ProjectPanel(project, drag, modifier)
        ToolWindow.Git -> GitPanel(project, drag, modifier)
        ToolWindow.Branches -> BranchesPanel(project, drag, modifier)
        ToolWindow.Terminal -> TerminalPanel(project, drag, modifier)
        ToolWindow.Network -> NetworkPanel(project, drag, modifier)
        ToolWindow.LanguageServers -> LanguageServersPanel(project, drag, modifier)
    }
}

@Composable
private fun stackedWindowSplitter(project: ProjectState, dock: Dock, before: ToolWindow, after: ToolWindow) {
    val layout = project.toolWindows
    val density = LocalDensity.current
    // Windows stacked vertically are separated by a horizontal bar; side by side at the bottom by a vertical one.
    val horizontalBar = dock != Dock.Bottom
    val total = layout.weightOf(before) + layout.weightOf(after)
    val dockSize = with(density) { layout.sizeOf(dock).dp.toPx() }
    SplitterBox(
        horizontalBar = horizontalBar,
        tag = null,
        onDrag = { delta ->
            if (dockSize > 0f) layout.resizeSplit(before, after, delta / dockSize * total)
        },
    )
}

@Composable
private fun dockResizeSplitter(project: ProjectState, dock: Dock) {
    val density = LocalDensity.current
    val horizontalBar = dock == Dock.Bottom
    SplitterBox(horizontalBar = horizontalBar, tag = Tags.dockSplitter(dock.name)) { delta ->
        val dp = with(density) { delta.toDp().value }
        project.toolWindows.resize(dock, if (dock == Dock.Right || dock == Dock.Bottom) -dp else dp)
    }
}
