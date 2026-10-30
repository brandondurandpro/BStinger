package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * Panels around the editors; their buttons stay in the left strip wherever the panels are docked. Network is a
 * devtools panel: its button only appears once Ctrl+Alt+Shift+D has shown it.
 */
enum class ToolWindow { Project, Git, Terminal, Network }

/** Where tool windows can be docked. */
enum class Dock { Left, Right, Bottom }

private const val MIN_DOCK = 80f
private const val MAX_DOCK = 1200f
private const val MIN_WEIGHT = 0.1f

/**
 * Layout of the tool windows: dock, order inside the dock, visibility and sizes. By default the Project and Git
 * panels share the left column (Project above Git) and the terminal is at the bottom.
 */
@Stable
class ToolWindowLayout {
    private val docks =
        mutableStateMapOf(
            ToolWindow.Project to Dock.Left,
            ToolWindow.Git to Dock.Left,
            ToolWindow.Terminal to Dock.Bottom,
            ToolWindow.Network to Dock.Bottom,
        )
    private val order =
        mutableStateListOf(ToolWindow.Project, ToolWindow.Git, ToolWindow.Terminal, ToolWindow.Network)
    private val shown =
        mutableStateMapOf(
            ToolWindow.Project to true,
            ToolWindow.Git to false,
            ToolWindow.Terminal to false,
            ToolWindow.Network to false,
        )
    private val sizes = mutableStateMapOf(Dock.Left to 280f, Dock.Right to 280f, Dock.Bottom to 240f)
    private val weights = mutableStateMapOf<ToolWindow, Float>()

    fun isVisible(window: ToolWindow): Boolean = shown[window] == true

    fun setVisible(window: ToolWindow, visible: Boolean) {
        shown[window] = visible
    }

    fun toggle(window: ToolWindow) = setVisible(window, !isVisible(window))

    fun dockOf(window: ToolWindow): Dock = docks.getValue(window)

    /** Visible windows of [dock], in display order (top to bottom, or left to right at the bottom). */
    fun visibleIn(dock: Dock): List<ToolWindow> = order.filter { docks[it] == dock && isVisible(it) }

    /** Moves [window] to [dock], first or last of it, and shows it. Buttons do not move: they are not part of it. */
    fun move(window: ToolWindow, dock: Dock, first: Boolean) {
        docks[window] = dock
        order.remove(window)
        if (first) order.add(0, window) else order.add(window)
        shown[window] = true
    }

    /** Width (left, right) or height (bottom) of [dock], in dp. */
    fun sizeOf(dock: Dock): Float = sizes.getValue(dock)

    fun resize(dock: Dock, delta: Float) {
        sizes[dock] = (sizeOf(dock) + delta).coerceIn(MIN_DOCK, MAX_DOCK)
    }

    /** Share of its dock taken by [window] (relative to the other visible windows of the dock). */
    fun weightOf(window: ToolWindow): Float = weights[window] ?: 1f

    /** Moves the split between [before] and [after] by [delta] (in weight units). */
    fun resizeSplit(before: ToolWindow, after: ToolWindow, delta: Float) {
        val total = weightOf(before) + weightOf(after)
        val newBefore = (weightOf(before) + delta).coerceIn(MIN_WEIGHT * total, (1 - MIN_WEIGHT) * total)
        weights[before] = newBefore
        weights[after] = total - newBefore
    }
}
