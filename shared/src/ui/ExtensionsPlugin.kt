package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.BUILT_IN_TOOL_WINDOWS
import org.example.kotlineditor.state.ExtensionTarget
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.SettingsState
import org.example.kotlineditor.state.ToolWindow

/** The Extensions tool window: its button, with a puzzle icon, sits at the top of the left strip. */
internal fun extensionsToolWindow(): ToolWindowContribution {
    val found = BUILT_IN_TOOL_WINDOWS.firstOrNull { it.window == ToolWindow.Extensions }
    val defaults = checkNotNull(found) { "No defaults for ${ToolWindow.Extensions}" }
    return ToolWindowContribution(
        defaults = defaults,
        tag = AppTags.TOOL_EXTENSIONS,
        group = StripGroup.Top,
        icon = Lucide.Puzzle,
        // Every project of the IDE is a ProjectState.
        content = { host, drag, modifier -> ExtensionsPanel(host as ProjectState, drag, modifier) },
    )
}

private val Good get() = IdeColors.GitAdded

/**
 * The installed extensions as cards: the plugins, then the language servers of the IDE. A card opens its extension in
 * a tab of the editor, where it is turned off or on; the field that installs a plugin archive is under the cards.
 */
@Composable
internal fun ExtensionsPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val settings = project.extensionServers
    ReloadServersOnConnection(project, settings)
    Column(modifier.fillMaxSize().background(IdeColors.Panel).testTag(AppTags.EXTENSIONS)) {
        ToolWindowHeader("Extensions", ToolWindow.Extensions, project.toolWindows, drag) {
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Extensions, false) },
                Modifier.testTag(AppTags.EXTENSIONS_HIDE),
            ) { CloseIcon() }
        }
        val scrolling = Modifier.weight(1f).fillMaxWidth()
        Column(
            scrolling.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExtensionList(project.installedPlugins(), settings) { project.editor.openExtension(it) }
        }
    }
}

// A card: a dot lit while the extension is on, its name and a line under it, its state on the right.
@Composable
internal fun ExtensionCard(target: ExtensionTarget, subtitle: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .card()
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(AppTags.extension(target.key))
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(if (on) Good else IdeColors.TextSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(target.title, fontWeight = FontWeight.SemiBold)
            Label(subtitle, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        Label(if (on) "On" else "Off", color = if (on) Good else IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

/** The key of the extension tab of the plugin [id]. */
internal fun pluginKey(id: String) = "$PLUGIN_PREFIX$id"

/** The key of the extension tab of the language servers of [language]. */
internal fun serversKey(language: String) = "$SERVERS_PREFIX$language"

internal const val PLUGIN_PREFIX = "plugin:"
internal const val SERVERS_PREFIX = "servers:"

// The server in use, or why there is none.
internal fun serverSubtitle(choice: LanguageServerChoice): String {
    if (choice.disabled) return "Turned off"
    val chosen = choice.candidates.firstOrNull { it.id == choice.chosen }
    return chosen?.displayName ?: "No server chosen yet"
}

/**
 * Asks the server which language servers it offers, at the first composition and again at each new connection: a
 * server that restarted, or a link that was down when the window opened, answers on the next one.
 */
@Composable
internal fun ReloadServersOnConnection(project: ProjectState, settings: SettingsState?) {
    val connections by project.services.connections.collectAsState()
    LaunchedEffect(settings, connections) { settings?.loadServers(connections) }
}
