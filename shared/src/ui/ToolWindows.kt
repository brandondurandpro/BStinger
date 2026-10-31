package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowLayout
import java.awt.Cursor

private val STRIP_WIDTH = 40.dp
private val HEADER_HEIGHT = 34.dp
internal val SPLITTER_SIZE = 5.dp

private const val DOCK_EDGE_FRACTION = 0.25f
private const val BOTTOM_DOCK_START = 0.65f

/**
 * Drag of a tool window header towards another dock. The buttons of the left strip never move: only the panel
 * is docked, so the strip stays a fixed, predictable place to find every tool window.
 */
@Stable
internal class ToolWindowDrag {
    var window by mutableStateOf<ToolWindow?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set

    /** Bounds of the whole tool window area, in root coordinates. */
    var workspace by mutableStateOf(Rect.Zero)

    private var moved = false

    fun start(window: ToolWindow, at: Offset) {
        this.window = window
        position = at
        moved = false
    }

    fun moveTo(at: Offset) {
        position = at
        moved = true
    }

    fun cancel() {
        window = null
        moved = false
    }

    /** Dock under the pointer, or `null` when dropped in the middle of the editors (no move). */
    fun targetDock(): Dock? {
        val area = workspace
        if (window == null || !moved || area.width <= 0f) return null
        return when {
            position.x < area.left + area.width * DOCK_EDGE_FRACTION -> Dock.Left
            position.x > area.right - area.width * DOCK_EDGE_FRACTION -> Dock.Right
            position.y > area.top + area.height * BOTTOM_DOCK_START -> Dock.Bottom
            else -> null
        }
    }

    /** Top half of a column, left half of the bottom dock: the panel goes before the others. */
    fun goesFirst(dock: Dock): Boolean =
        if (dock == Dock.Bottom) position.x < workspace.center.x else position.y < workspace.center.y

    fun drop(layout: ToolWindowLayout) {
        val dropped = window
        if (dropped != null) targetDock()?.let { layout.move(dropped, it, goesFirst(it)) }
        window = null
        moved = false
    }
}

/** Fixed left strip: one button per tool window, wherever the panel itself is docked. */
@Composable
internal fun ToolStrip(project: ProjectState, devtools: Boolean) {
    Column(
        Modifier.width(STRIP_WIDTH).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
        ToolButton(project, ToolWindow.Terminal, Tags.TOOL_TERMINAL) {
            Label(">_", color = IdeColors.Text, fontWeight = FontWeight.Bold)
        }
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

@Composable
internal fun SplitterBox(horizontalBar: Boolean, tag: String?, onDrag: (Float) -> Unit) {
    val size =
        if (horizontalBar) {
            Modifier.fillMaxWidth().height(SPLITTER_SIZE)
        } else {
            Modifier.fillMaxHeight().width(SPLITTER_SIZE)
        }
    val base =
        Modifier
            .then(size)
            .pointerHoverIcon(
                PointerIcon(Cursor(if (horizontalBar) Cursor.N_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR)),
            )
    val dragging =
        base.draggable(
            rememberDraggableState { delta -> onDrag(delta) },
            orientation = if (horizontalBar) Orientation.Vertical else Orientation.Horizontal,
        )
    val splitter = if (tag != null) dragging.testTag(tag) else dragging
    Box(splitter, contentAlignment = Alignment.Center) {
        if (horizontalBar) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
        } else {
            Box(Modifier.fillMaxHeight().width(1.dp).background(IdeColors.Border))
        }
    }
}

/** Header of a tool window: its title, actions, and the drag that moves the panel to another dock. */
@Composable
internal fun ToolWindowHeader(
    title: String,
    window: ToolWindow,
    project: ProjectState,
    drag: ToolWindowDrag,
    modifier: Modifier = Modifier,
    middle: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val header =
        modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(IdeColors.Panel)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(window) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val at = origin + change.position
                        val dragging = drag.window == window
                        when {
                            // Presses on the header buttons are theirs: only the bare header starts a drag.
                            event.type == PointerEventType.Press && !change.isConsumed -> {
                                change.consume()
                                drag.start(window, at)
                            }

                            event.type == PointerEventType.Move && dragging -> {
                                change.consume()
                                drag.moveTo(at)
                            }

                            event.type == PointerEventType.Release && dragging -> {
                                drag.drop(project.toolWindows)
                            }
                        }
                    }
                }
            }
    Row(
        header.testTag(Tags.toolHeader(window.name)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (middle == null) {
            Label(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        } else {
            Label(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
            middle()
        }
        trailing()
    }
}

/** Highlight of the dock that would receive the dragged panel. */
@Composable
internal fun DropOverlay(drag: ToolWindowDrag) {
    val dock = drag.targetDock() ?: return
    Canvas(Modifier.fillMaxSize()) {
        val area = drag.workspace
        val strip = STRIP_WIDTH.toPx()
        val left = strip
        val width = area.width - strip
        val rect =
            when (dock) {
                Dock.Left -> Rect(left, 0f, left + width * DOCK_EDGE_FRACTION, area.height)
                Dock.Right -> Rect(left + width * (1 - DOCK_EDGE_FRACTION), 0f, area.width, area.height)
                Dock.Bottom -> Rect(left, area.height * BOTTOM_DOCK_START, area.width, area.height)
            }
        drawRect(IdeColors.Accent.copy(alpha = 0.18f), topLeft = rect.topLeft, size = rect.size)
        drawRect(
            IdeColors.Accent.copy(alpha = 0.6f),
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(2.dp.toPx()),
        )
    }
}
