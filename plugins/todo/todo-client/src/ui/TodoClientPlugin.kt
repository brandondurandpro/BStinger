package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the bundled Todo feature: its tool window. */
class TodoClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(todoToolWindow())
    }
}
