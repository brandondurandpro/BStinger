package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.LanguageServersHost
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults

/** The Language Servers tool window: the servers running for the project and their state. */
fun languageServersToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.LanguageServers, Dock.Bottom),
        tag = LanguageServerTags.TOOL_BUTTON,
        group = StripGroup.Bottom,
        icon = Lucide.Braces,
        content = { host, drag, modifier -> LanguageServersPanel(host as LanguageServersHost, drag, modifier) },
    )
