package org.example.kotlineditor.plugin

import org.example.kotlineditor.ui.KeyBinding

/** The client half of a plugin: the class named by `client` in its manifest implements it. */
interface ClientPlugin {
    /** Called once when the plugin loads; everything it registers is taken away when it unloads. */
    fun register(contributions: ClientContributions)
}

/** What a [ClientPlugin] registers. Nothing is visible until [register] returns. */
class ClientContributions {
    private val windows = mutableListOf<ToolWindowContribution>()
    private val keys = mutableListOf<KeyBinding<ActionContext>>()
    private val cleanups = mutableListOf<AutoCloseable>()

    /** Adds a tool window. */
    fun toolWindow(window: ToolWindowContribution) {
        windows += window
    }

    /** Adds a shortcut of the normal workspace. */
    fun shortcut(binding: KeyBinding<ActionContext>) {
        keys += binding
    }

    /** Runs [cleanup] when the plugin unloads, after its contributions are removed (cancel a scope, close a file). */
    fun onUnload(cleanup: AutoCloseable) {
        cleanups += cleanup
    }

    /** The contributions as the data [IdePlugin] the IDE loads. */
    fun toPlugin(id: String): IdePlugin = IdePlugin(id, windows.toList(), keys.toList())

    /** The cleanups, last registered first. */
    fun cleanups(): List<AutoCloseable> = cleanups.reversed()
}
