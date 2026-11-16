package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the bundled Run feature: its tool window, its shortcuts, and its widget in the toolbar. */
class RunClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(runToolWindow())
        runShortcuts().forEach(contributions::shortcut)
        contributions.toolbarButton(runToolbarButton())
    }
}
