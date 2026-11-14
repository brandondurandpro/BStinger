package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * A panel around the editors; their buttons stay in the left strip wherever the panels are docked. Network is a
 * devtools panel: its button only appears once Ctrl+Alt+Shift+D has shown it. The built-in windows are the constants
 * of the companion; a plugin makes its own with an id of its own, and announces it with [ToolWindowDefaults].
 */
@Immutable
data class ToolWindow(
    /** Stable id, saved in the layout preferences. */
    val name: String,
) {
    /** The built-in windows. */
    companion object {
        /** Files of the project. */
        val Project = ToolWindow("Project")

        /** Local changes and commit. */
        val Git = ToolWindow("Git")

        /** Branches and history. */
        val Branches = ToolWindow("Branches")

        /** Run configurations and their output. */
        val Run = ToolWindow("Run")

        /** Terminal. */
        val Terminal = ToolWindow("Terminal")

        /** Devtools: the requests of the protocol. */
        val Network = ToolWindow("Network")

        /** Language servers. */
        val LanguageServers = ToolWindow("LanguageServers")

        /** Extensions: the plugins installed, to turn on or off, and the field that installs one. */
        val Extensions = ToolWindow("Extensions")
    }
}

/** Where a tool window sits until the user moves it, and whether it starts shown. */
@Immutable
data class ToolWindowDefaults(val window: ToolWindow, val dock: Dock, val shown: Boolean = false)

/** The defaults of the built-in tool windows, in the order of their buttons. */
val BUILT_IN_TOOL_WINDOWS: List<ToolWindowDefaults> =
    listOf(
        ToolWindowDefaults(ToolWindow.Project, Dock.Left, shown = true),
        ToolWindowDefaults(ToolWindow.Git, Dock.Left),
        ToolWindowDefaults(ToolWindow.Branches, Dock.Bottom),
        ToolWindowDefaults(ToolWindow.Extensions, Dock.Left),
        ToolWindowDefaults(ToolWindow.Run, Dock.Bottom),
        ToolWindowDefaults(ToolWindow.Terminal, Dock.Bottom),
        ToolWindowDefaults(ToolWindow.Network, Dock.Bottom),
        ToolWindowDefaults(ToolWindow.LanguageServers, Dock.Bottom),
    )

/** Where tool windows can be docked. */
enum class Dock { Left, Right, Bottom }

private const val MIN_DOCK = 80f
private const val MAX_DOCK = 1200f
private const val MIN_WEIGHT = 0.1f
private const val MIN_PANE = 120f
private const val MAX_PANE = 900f
private const val HISTORY_BRANCHES_WIDTH = 230f
private const val HISTORY_DETAILS_WIDTH = 320f

/** Side panes inside a tool window, whose width the user sets with a splitter; kept with the layout. */
enum class Pane(val defaultWidth: Float) {
    /** Branch list of the Branches window. */
    HistoryBranches(HISTORY_BRANCHES_WIDTH),

    /** Commit details of the Branches window. */
    HistoryDetails(HISTORY_DETAILS_WIDTH),
}

/**
 * Layout of the tool windows: dock, order inside the dock, visibility and sizes. By default the Project and Git
 * panels share the left column (Project above Git) and the terminal is at the bottom.
 */
@Stable
@Suppress("TooManyFunctions")
class ToolWindowLayout(defaults: List<ToolWindowDefaults> = BUILT_IN_TOOL_WINDOWS) {
    private val docks = mutableStateMapOf<ToolWindow, Dock>()
    private val order = mutableStateListOf<ToolWindow>()
    private val shown = mutableStateMapOf<ToolWindow, Boolean>()
    private val sizes = mutableStateMapOf(Dock.Left to 280f, Dock.Right to 280f, Dock.Bottom to 240f)
    private val weights = mutableStateMapOf<ToolWindow, Float>()
    private val panes = mutableStateMapOf<Pane, Float>()

    init {
        defaults.forEach(::add)
    }

    /** The windows the layout knows, in display order. */
    val windows: List<ToolWindow> get() = order

    /** Adds a window (a plugin's); one the layout already has is left as the user arranged it. */
    fun add(defaults: ToolWindowDefaults) {
        val window = defaults.window
        if (window in order) return
        docks[window] = defaults.dock
        shown[window] = defaults.shown
        order.add(window)
    }

    /** Removes a window (a plugin unloading), with its place and its size. */
    fun remove(window: ToolWindow) {
        order.remove(window)
        docks.remove(window)
        shown.remove(window)
        weights.remove(window)
    }

    /** Width of [pane] inside its tool window, in dp; the default until the user drags its splitter. */
    fun widthOf(pane: Pane): Float = panes[pane] ?: pane.defaultWidth

    /** Widens [pane] by [delta] dp (negative narrows it), within the pane's limits. */
    fun resizePane(pane: Pane, delta: Float) {
        panes[pane] = (widthOf(pane) + delta).coerceIn(MIN_PANE, MAX_PANE)
    }

    fun isVisible(window: ToolWindow): Boolean = shown[window] == true

    fun setVisible(window: ToolWindow, visible: Boolean) {
        shown[window] = visible
    }

    fun toggle(window: ToolWindow) = setVisible(window, !isVisible(window))

    fun dockOf(window: ToolWindow): Dock = docks[window] ?: Dock.Bottom

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

    /** The layout as [PreferenceKeys.LAYOUT_PREFIX] preferences. */
    fun toPreferences(): Map<String, String> =
        buildMap {
            put(ORDER_KEY, order.joinToString(",") { it.name })
            order.toList().forEach { window ->
                put("$DOCK_KEY.${window.name}", dockOf(window).name)
                put("$VISIBLE_KEY.${window.name}", isVisible(window).toString())
                weights[window]?.let { put("$WEIGHT_KEY.${window.name}", it.toString()) }
            }
            Dock.entries.forEach { put("$SIZE_KEY.${it.name}", sizeOf(it).toString()) }
            panes.forEach { (pane, width) -> put("$PANE_KEY.${pane.name}", width.toString()) }
        }

    /** Applies what [toPreferences] saved; absent or invalid entries keep the current value. */
    fun restore(preferences: Map<String, String>) {
        order.toList().forEach { window ->
            val name = window.name
            Dock.entries.find { it.name == preferences["$DOCK_KEY.$name"] }?.let { docks[window] = it }
            preferences["$VISIBLE_KEY.$name"]?.toBooleanStrictOrNull()?.let { shown[window] = it }
            val weight = preferences["$WEIGHT_KEY.$name"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            if (weight != null) weights[window] = weight
        }
        Dock.entries.forEach { dock ->
            val size = preferences["$SIZE_KEY.${dock.name}"]?.toFloatOrNull()
            if (size != null && size.isFinite()) sizes[dock] = size.coerceIn(MIN_DOCK, MAX_DOCK)
        }
        Pane.entries.forEach { pane ->
            val width = preferences["$PANE_KEY.${pane.name}"]?.toFloatOrNull()
            if (width != null && width.isFinite()) panes[pane] = width.coerceIn(MIN_PANE, MAX_PANE)
        }
        val names = preferences[ORDER_KEY].orEmpty().split(',')
        val saved = names.mapNotNull { name -> windows.find { it.name == name } }
        val restored = (saved + order).distinct()
        order.clear()
        order.addAll(restored)
    }

    private companion object {
        const val ORDER_KEY = "${PreferenceKeys.LAYOUT_PREFIX}order"
        const val DOCK_KEY = "${PreferenceKeys.LAYOUT_PREFIX}dock"
        const val VISIBLE_KEY = "${PreferenceKeys.LAYOUT_PREFIX}visible"
        const val WEIGHT_KEY = "${PreferenceKeys.LAYOUT_PREFIX}weight"
        const val SIZE_KEY = "${PreferenceKeys.LAYOUT_PREFIX}size"
        const val PANE_KEY = "${PreferenceKeys.LAYOUT_PREFIX}pane"
    }
}
