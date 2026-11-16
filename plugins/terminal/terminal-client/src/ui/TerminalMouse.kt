package org.example.kotlineditor.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.example.kotlineditor.model.TerminalMouseAction
import org.example.kotlineditor.model.TerminalMouseButton
import org.example.kotlineditor.model.TerminalMouseEvent
import org.example.kotlineditor.state.TerminalTab

// Rows the wheel moves per scroll event.
private const val WHEEL_ROWS = 3

/** Focus, clicks, drag selection, wheel scrolling and reporting of the grid. */
internal fun Modifier.terminalPointerInput(
    tab: TerminalTab,
    focus: FocusRequester,
    metrics: CellMetrics,
    selection: TerminalSelection,
): Modifier {
    val held = HeldButton()
    return pointerInput(tab, focus, metrics) { terminalMouseClicks(tab, focus, metrics, held) }
        .pointerInput(tab, metrics) { terminalMouseWheel(tab, metrics) }
        .pointerInput(tab, metrics, selection, held) {
            detectDragGestures(
                onDragStart = { at ->
                    if (held.taken == null) selection.start(cellAt(at, metrics))
                },
                onDrag = { change, _ ->
                    if (held.taken == null) {
                        selection.moveTo(cellAt(change.position, metrics))
                        change.consume()
                    }
                },
            )
        }
}

// The button the program owns for the press currently held, `null` when none does: the drag selection gives up
// the mouse for as long as a program that tracks it holds a press.
private class HeldButton {
    var taken: TerminalMouseButton? = null
}

// Presses, releases and moves of the grid. Every press takes the focus, and a program that enabled mouse
// tracking receives the events, its drag included. Shift gives the mouse back to the user, the way xterm lets a
// user take it from a program, and the selection runs instead.
private suspend fun PointerInputScope.terminalMouseClicks(
    tab: TerminalTab,
    focus: FocusRequester,
    metrics: CellMetrics,
    held: HeldButton,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val cell = cellOf(event, metrics)
            when (event.type) {
                PointerEventType.Press -> press(tab, focus, event, cell, held)
                PointerEventType.Release -> release(tab, event, cell, held)
                PointerEventType.Move -> move(tab, event, cell, held)
                else -> Unit
            }
        }
    }
}

private fun press(tab: TerminalTab, focus: FocusRequester, event: PointerEvent, cell: CellPos?, held: HeldButton) {
    runCatching { focus.requestFocus() }
    val button = mouseButton(event)
    if (button == null || cell == null) return
    if (!tab.tracksMouse() || event.shift()) return
    held.taken = button
    report(tab, event, TerminalMouseAction.Press, button, cell)
}

private fun release(tab: TerminalTab, event: PointerEvent, cell: CellPos?, held: HeldButton) {
    val button = held.taken
    held.taken = null
    if (button == null || cell == null) return
    if (!tab.tracksMouse()) return
    report(tab, event, TerminalMouseAction.Release, button, cell)
}

private fun move(tab: TerminalTab, event: PointerEvent, cell: CellPos?, held: HeldButton) {
    val button = held.taken
    if (button == null || cell == null) return
    if (!tab.tracksMouse()) return
    report(tab, event, TerminalMouseAction.Move, button, cell)
}

// Reports the event to the program and consumes it, so the selection never starts over it.
private fun report(
    tab: TerminalTab,
    event: PointerEvent,
    action: TerminalMouseAction,
    button: TerminalMouseButton,
    cell: CellPos,
) {
    tab.mouse(TerminalMouseEvent(action = action, button = button, column = cell.column, row = cell.row))
    event.changes.forEach { it.consume() }
}

// The cell of the grid the pointer is on, `null` when it is outside.
private fun cellOf(event: PointerEvent, metrics: CellMetrics): CellPos? =
    event.changes.firstOrNull()?.let { cellAt(it.position, metrics) }

// The button the user pressed, or `null` for a button the terminal does not report.
private fun mouseButton(event: PointerEvent): TerminalMouseButton? {
    val buttons = event.buttons
    return when {
        buttons.isPrimaryPressed -> TerminalMouseButton.Left
        buttons.isSecondaryPressed -> TerminalMouseButton.Right
        buttons.isTertiaryPressed -> TerminalMouseButton.Middle
        else -> null
    }
}

private fun PointerEvent.shift(): Boolean = keyboardModifiers.isShiftPressed

private fun TerminalTab.tracksMouse(): Boolean = snapshot?.mouseTracking == true

private suspend fun PointerInputScope.terminalMouseWheel(tab: TerminalTab, metrics: CellMetrics) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll) {
                val change = event.changes.firstOrNull()
                val delta = change?.scrollDelta?.y ?: 0f
                if (delta != 0f) {
                    val cell = change?.let { cellAt(it.position, metrics) }
                    val rows = if (delta < 0f) WHEEL_ROWS else -WHEEL_ROWS
                    tab.wheel(rows, cell?.column ?: 0, cell?.row ?: 0)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
