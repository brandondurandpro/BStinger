package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the bundled Find in Files feature: its side panel in the left strip. */
class SearchClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(searchToolWindow())
    }
}
