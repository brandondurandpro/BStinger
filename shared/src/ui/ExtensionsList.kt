package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.state.ClientPlugins
import org.example.kotlineditor.state.ExtensionTarget
import org.example.kotlineditor.state.PluginEntry
import org.example.kotlineditor.state.SERVER_LANGUAGES
import org.example.kotlineditor.state.SettingsState

@Composable
internal fun ExtensionList(plugins: ClientPlugins?, settings: SettingsState?, open: (ExtensionTarget) -> Unit) {
    var query by remember { mutableStateOf("") }
    IdeTextField(
        query,
        { query = it },
        Modifier.fillMaxWidth().testTag(AppTags.EXTENSIONS_SEARCH),
        placeholder = "Search extensions",
        leading = { SearchIcon() },
    )
    val entries = plugins?.entries.orEmpty().filter { matches(query, it.name, it.id) }
    val offered = settings?.let { servers -> SERVER_LANGUAGES.mapNotNull { servers.serversOf(it) } }.orEmpty()
    val servers = offered.filter { it.candidates.isNotEmpty() && matchesServers(query, it) }
    if (plugins == null) {
        Label("Plugins are not available here.", color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
    PluginCards(entries, open)
    ServerCards(servers, open)
    settings?.serverError?.let {
        Label(it, Modifier.testTag(AppTags.EXTENSIONS_ERROR), color = IdeColors.Error, fontSize = 12.sp, maxLines = 3)
    }
    val nothingShown = entries.isEmpty() && servers.isEmpty()
    if (nothingShown && !isWaiting(plugins, settings)) EmptyNote(query)
}

@Composable
private fun PluginCards(entries: List<PluginEntry>, open: (ExtensionTarget) -> Unit) {
    if (entries.isEmpty()) return
    SectionLabel("Plugins")
    for (entry in entries) {
        val target = ExtensionTarget(pluginKey(entry.id), entry.name)
        ExtensionCard(target, entry.version.ifBlank { "No version" }, entry.running) { open(target) }
    }
}

@Composable
private fun ServerCards(servers: List<LanguageServerChoice>, open: (ExtensionTarget) -> Unit) {
    if (servers.isEmpty()) return
    SectionLabel("Language servers")
    for (choice in servers) {
        val target = ExtensionTarget(serversKey(choice.language), choice.languageName)
        ExtensionCard(target, serverSubtitle(choice), !choice.disabled) { open(target) }
    }
}

// Nothing to show yet: no plugin source, or the servers are still being asked (an error says it when they cannot be).
private fun isWaiting(plugins: ClientPlugins?, settings: SettingsState?): Boolean {
    if (plugins == null && settings == null) return false
    val asking = settings != null && settings.serverError == null && !settings.loaded
    return asking
}

@Composable
private fun EmptyNote(query: String) {
    val text = if (query.isBlank()) "No extension installed." else "No extension matches \"${query.trim()}\"."
    Label(text, Modifier.testTag(AppTags.EXTENSIONS_EMPTY), color = IdeColors.TextSecondary, fontSize = 12.sp)
}

// Every word of the search is in one of the [texts], whatever the case.
private fun matches(query: String, vararg texts: String): Boolean {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val haystack = texts.joinToString(" ").lowercase()
    return words.all { it in haystack }
}

// A language is found by its name and by the servers it offers (`phpactor`, `intelephense`).
private fun matchesServers(query: String, choice: LanguageServerChoice): Boolean {
    val servers = choice.candidates.map { it.displayName }
    return matches(query, choice.languageName, choice.language, *servers.toTypedArray())
}
