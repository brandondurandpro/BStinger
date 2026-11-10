package org.example.kotlineditor.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
        icon = { Label("LS", color = IdeColors.Text, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
        content = { host, drag, modifier -> LanguageServersPanel(host as LanguageServersHost, drag, modifier) },
    )
