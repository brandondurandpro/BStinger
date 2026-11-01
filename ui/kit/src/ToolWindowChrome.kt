package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowLayout
import java.awt.Cursor

private const val DOCK_EDGE_FRACTION = 0.25f

private const val BOTTOM_DOCK_START = 0.65f

/**
 * Drag of a tool window header towards another dock. The buttons of the left strip never move: only the panel
 * is docked, so the strip stays a fixed, predictable place to find every tool window.
 */
@Stable
class ToolWindowDrag {
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
        val dragging = window != null && moved
        if (!dragging || area.width <= 0f) return null
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

/** Header of a tool window: its title, actions, and the drag that moves the panel to another dock. */
@Composable
fun ToolWindowHeader(
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
fun DropOverlay(drag: ToolWindowDrag) {
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

/** Width of the strips of tool window buttons on the sides. */
val STRIP_WIDTH = 40.dp

/** Height of the header of a tool window. */
val HEADER_HEIGHT = 34.dp

val SPLITTER_SIZE = 5.dp

@Composable
fun SplitterBox(horizontalBar: Boolean, tag: String?, onDrag: (Float) -> Unit) {
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
