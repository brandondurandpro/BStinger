package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.state.ClientPlugins
import org.example.kotlineditor.state.ExtensionTarget
import org.example.kotlineditor.state.PluginEntry
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.SettingsState

private val TAB_MAX_WIDTH = 760.dp
private val Good get() = IdeColors.GitAdded

/**
 * The tab of an extension: a plugin of the folder, or the language servers of a language. Its title, its state and the
 * button that turns it off or on are on top, what it is made of under them.
 */
@Composable
internal fun ExtensionTabView(project: ProjectState, target: ExtensionTarget, modifier: Modifier = Modifier) {
    val settings = project.extensionServers
    ReloadServersOnConnection(project, settings)
    Box(modifier.background(IdeColors.Editor).testTag(AppTags.EXTENSION_TAB), contentAlignment = Alignment.TopCenter) {
        val width = Modifier.widthIn(max = TAB_MAX_WIDTH).fillMaxWidth()
        Column(
            width.verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (target.key.startsWith(SERVERS_PREFIX)) {
                val language = target.key.removePrefix(SERVERS_PREFIX)
                val choice = settings?.serversOf(language)
                if (settings != null && choice != null) ServersDetail(settings, language, choice)
            } else {
                PluginDetail(target.key.removePrefix(PLUGIN_PREFIX), project.installedPlugins())
            }
        }
    }
}

// The title, a line under it, the state and the actions on the right.
@Composable
private fun DetailHeader(title: String, subtitle: String, on: Boolean, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(if (on) Good else IdeColors.TextSecondary)
                Label(subtitle, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
        }
        actions()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

@Composable
private fun DetailLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Label(name, Modifier.widthIn(min = 96.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
        Label(value, Modifier.weight(1f), fontSize = 12.sp, maxLines = 3)
    }
}

@Composable
private fun PluginDetail(id: String, plugins: ClientPlugins?) {
    val entry = plugins?.entries?.firstOrNull { it.id == id }
    if (plugins == null || entry == null) {
        Label("This plugin is not installed.", color = IdeColors.TextSecondary)
        return
    }
    val background = rememberBackground()
    Column(
        Modifier.fillMaxWidth().testTag(AppTags.plugin(entry.id)).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val status = if (entry.running) "Running" else entry.offBecause.ifBlank { "Off" }
        DetailHeader(entry.name, status, entry.running) { PluginActions(entry, plugins, background) }
        PluginFacts(entry)
    }
}

@Composable
private fun PluginActions(entry: PluginEntry, plugins: ClientPlugins, background: (() -> Unit) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IdeButton(
            "Reload",
            { background { plugins.reload(entry.id) } },
            Modifier.testTag(AppTags.pluginUpdate(entry.id)),
            enabled = entry.running,
        )
        IdeButton(
            if (entry.running) "Disable" else "Enable",
            { background { plugins.setEnabled(entry.id, !entry.running) } },
            Modifier.testTag(AppTags.pluginToggle(entry.id)),
            primary = !entry.running,
        )
    }
}

@Composable
private fun PluginFacts(entry: PluginEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Details")
        DetailLine("Id", entry.id)
        DetailLine("Version", entry.version.ifBlank { "No version" })
        DetailLine("State", if (entry.running) "Running" else entry.offBecause.ifBlank { "Off" })
    }
}

@Composable
private fun ServersDetail(settings: SettingsState, language: String, choice: LanguageServerChoice) {
    Column(
        Modifier.fillMaxWidth().testTag(AppTags.EXTENSIONS_SERVERS),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DetailHeader(choice.languageName, serverSubtitle(choice), !choice.disabled) {
            ServersToggleButton(settings, language, choice)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Servers")
            LanguageServerList(settings, language, choice, showHeader = false)
        }
    }
}
