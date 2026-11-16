package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.StatusIndicatorContribution
import org.example.kotlineditor.state.LanguageServersProjectView

/** The indicators of the status bar of the language servers feature: the servers, the memory and the CPU. */
class LanguageServersStatusPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.statusIndicator(serversIndicator())
        contributions.statusIndicator(memoryIndicator())
        contributions.statusIndicator(cpuIndicator())
    }
}

private fun serversIndicator() =
    StatusIndicatorContribution("LanguageServers", "Language servers") { project ->
        LanguageServerStatusItems(LanguageServersProjectView(project))
    }

private fun memoryIndicator() =
    StatusIndicatorContribution("Memory", "Memory") { project ->
        val view = LanguageServersProjectView(project)
        val state = view.languageServers
        if (state != null) IdeMemoryStatusItems(view, state)
    }

private fun cpuIndicator() =
    StatusIndicatorContribution("Cpu", "CPU") { project ->
        val view = LanguageServersProjectView(project)
        val state = view.languageServers
        if (state != null) IdeCpuStatusItem(view, state)
    }
