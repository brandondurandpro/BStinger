package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.plugin.ToolWindowRegistry
import org.example.kotlineditor.state.BUILT_IN_TOOL_WINDOWS
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow

private val BUTTON_GAP = 4.dp

/**
 * Fixed left strip: one button per tool window, wherever the panel itself is docked; [top] adds buttons above them
 * (the conversation list of agent mode).
 */
@Composable
internal fun ToolStrip(
    project: ProjectState?,
    devtools: Boolean,
    registry: ToolWindowRegistry = IdeRegistries.toolWindows,
    top: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.width(STRIP_WIDTH).fillMaxHeight().padding(vertical = 6.dp),
        // Apart, so that the backgrounds of several windows open at once never touch.
        verticalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        top()
        if (project != null) ToolButtons(project, devtools, registry)
    }
}

@Composable
private fun ColumnScope.ToolButtons(project: ProjectState, devtools: Boolean, registry: ToolWindowRegistry) {
    val contributions = registry.contributions
    // A window a plugin just registered is unknown to the layout until it is added.
    LaunchedEffect(contributions.toList()) { contributions.forEach { project.toolWindows.add(it.defaults) } }
    val shown = contributions.filter { devtools || !it.devtools }
    shown.filter { it.group == StripGroup.Top }.forEach { ToolButton(project, it.window, it.tag, it.icon) }
    Spacer(Modifier.weight(1f))
    // Bottom tool windows sit at the bottom of the strip, like in IntelliJ's New UI.
    shown.filter { it.group == StripGroup.Bottom }.forEach { ToolButton(project, it.window, it.tag, it.icon) }
}

// The button of a tool window: a muted icon, lit up on a filled background while its window is open.
@Composable
private fun ToolButton(project: ProjectState, window: ToolWindow, tag: String, icon: LucideIcon) {
    val active = project.toolWindows.isVisible(window)
    val background = if (active) IdeColors.Selection else Color.Transparent
    val tint = if (active) IdeColors.Text else IdeColors.TextSecondary
    Box(
        Modifier
            .size(30.dp)
            .background(background, IdeShapes.Large)
            .clickable { project.toggleToolWindow(window) }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        IdeIcon(icon, color = tint)
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
    val contribution = IdeRegistries.toolWindows.find(window) ?: return
    contribution.content(project, drag, modifier.island())
}

private fun builtIn(
    window: ToolWindow,
    group: StripGroup,
    tag: String,
    devtools: Boolean = false,
    icon: LucideIcon,
    content: @Composable (ProjectState, ToolWindowDrag, Modifier) -> Unit,
): ToolWindowContribution {
    val found = BUILT_IN_TOOL_WINDOWS.firstOrNull { it.window == window }
    val defaults = checkNotNull(found) { "No defaults for $window" }
    return ToolWindowContribution(
        defaults = defaults,
        tag = tag,
        group = group,
        devtools = devtools,
        icon = icon,
        // Every project of the IDE is a ProjectState.
        content = { host, drag, modifier -> content(host as ProjectState, drag, modifier) },
    )
}

/** The tool windows of the IDE itself, in the order of their buttons; Run comes from its own feature. */
internal fun builtInToolWindows(): List<ToolWindowContribution> =
    listOf(
        builtIn(
            ToolWindow.Network,
            StripGroup.Bottom,
            AppTags.TOOL_NETWORK,
            devtools = true,
            icon = Lucide.ArrowUpDown,
        ) { project, drag, modifier -> NetworkPanel(project, drag, modifier) },
    )

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
    SplitterBox(horizontalBar = horizontalBar, tag = AppTags.dockSplitter(dock.name)) { delta ->
        val dp = with(density) { delta.toDp().value }
        project.toolWindows.resize(dock, if (dock == Dock.Right || dock == Dock.Bottom) -dp else dp)
    }
}
