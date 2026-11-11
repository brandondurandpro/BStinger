package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.BUILT_IN_TOOL_WINDOWS
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow

/** The Network (devtools) tool window: the requests of the client to the server, in the bottom group of the strip. */
internal fun networkToolWindow(): ToolWindowContribution {
    val found = BUILT_IN_TOOL_WINDOWS.firstOrNull { it.window == ToolWindow.Network }
    val defaults = checkNotNull(found) { "No defaults for ${ToolWindow.Network}" }
    return ToolWindowContribution(
        defaults = defaults,
        tag = AppTags.TOOL_NETWORK,
        group = StripGroup.Bottom,
        devtools = true,
        icon = Lucide.ArrowUpDown,
        // Every project of the IDE is a ProjectState.
        content = { host, drag, modifier -> NetworkPanel(host as ProjectState, drag, modifier) },
    )
}
