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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowRegistry
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
    onSettings: () -> Unit,
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
        if (project != null) ToolButtons(project, devtools, registry) else Spacer(Modifier.weight(1f))
        SettingsButton(onSettings)
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

/** The height of a panel of a scrolling column, in dp, that the user sets with the splitter under it. */
internal class PanelHeights(private val default: Float) {
    private val heights = mutableStateMapOf<Any, Float>()
    private val collapsed = mutableStateMapOf<Any, Boolean>()

    fun isCollapsed(key: Any): Boolean = collapsed[key] == true

    fun toggle(key: Any) {
        collapsed[key] = !isCollapsed(key)
    }

    fun heightOf(key: Any): Dp = (heights[key] ?: default).dp

    fun resize(key: Any, delta: Float) {
        heights[key] = ((heights[key] ?: default) + delta).coerceIn(MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT)
    }
}

private const val MIN_PANEL_HEIGHT = 120f
private const val MAX_PANEL_HEIGHT = 2000f

/** A splitter under a panel of a scrolling column: dragging it down makes the panel taller. */
@Composable
internal fun PanelSplitter(heights: PanelHeights, key: Any, tag: String? = null) {
    val density = LocalDensity.current
    SplitterBox(horizontalBar = true, tag = tag) { delta ->
        heights.resize(key, with(density) { delta.toDp().value })
    }
}

/**
 * A card of a scrolling column, at the height the user gave it and with the splitter that changes it. Its own header
 * folds it (the chevron [FoldChevron] draws): folded, the card shrinks to that header.
 */
@Composable
internal fun CollapsibleCard(
    heights: PanelHeights,
    key: Any,
    tag: String,
    splitterTag: String?,
    content: @Composable (Modifier) -> Unit,
) {
    val folded = heights.isCollapsed(key)
    val height = if (folded) HEADER_HEIGHT else heights.heightOf(key)
    val fold = CardFold(folded, tag) { heights.toggle(key) }
    CompositionLocalProvider(LocalCardFold provides fold) {
        content(Modifier.fillMaxWidth().height(height).clipToBounds())
    }
    if (folded) Spacer(Modifier.height(ISLAND_GAP)) else PanelSplitter(heights, key, splitterTag)
}

/**
 * The left dock under another panel (the conversation list of agent mode), in a column that scrolls: each window
 * is a card that folds, and keeps its own height, which its splitter changes, instead of sharing the height left.
 */
@Composable
internal fun ToolDockBelow(project: ProjectState, drag: ToolWindowDrag, heights: PanelHeights) {
    // The changes come first: they are what the agent just did, the tree is for browsing.
    val windows = project.toolWindows.visibleIn(Dock.Left).sortedBy { if (it == ToolWindow.Git) 0 else 1 }
    windows.forEach { window ->
        val barTag = AppTags.dockCardBar(window.name)
        CollapsibleCard(heights, window, barTag, AppTags.dockSplitter(window.name)) { modifier ->
            ToolWindowContent(project, window, drag, modifier)
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
