package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ClientPlugins
import org.example.kotlineditor.state.PluginEntry
import java.io.File

/** The Plugins settings: the plugins of the folder with their switch, and the field that installs an archive. */
@Composable
internal fun PluginsControl(app: AppState) {
    val plugins = app.plugins
    if (plugins == null) {
        Label("Plugins are not available here.", color = IdeColors.TextSecondary)
        return
    }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (plugins.entries.isEmpty()) Label("No plugin installed.", color = IdeColors.TextSecondary)
        for (entry in plugins.entries) {
            PluginRow(entry, plugins) { scope.launch { withContext(Dispatchers.IO) { it() } } }
        }
        InstallRow(plugins, onError = { error = it }) { scope.launch { withContext(Dispatchers.IO) { it() } } }
        if (error.isNotBlank()) Label(error, Modifier.testTag(AppTags.PLUGIN_ERROR), color = IdeColors.Error)
    }
}

@Composable
private fun PluginRow(entry: PluginEntry, plugins: ClientPlugins, background: (() -> Unit) -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag(AppTags.plugin(entry.id)).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("${entry.name} ${entry.version}".trim(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IdeButton(
                if (entry.running) "Disable" else "Enable",
                { background { plugins.setEnabled(entry.id, !entry.running) } },
                Modifier.testTag(AppTags.pluginToggle(entry.id)),
            )
            IdeButton(
                "Reload",
                { background { plugins.reload(entry.id) } },
                Modifier.testTag(AppTags.pluginUpdate(entry.id)),
                enabled = entry.running,
            )
        }
        val status = if (entry.running) "Running" else entry.offBecause.ifBlank { "Off" }
        Label(status, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun InstallRow(plugins: ClientPlugins, onError: (String) -> Unit, background: (() -> Unit) -> Unit) {
    var path by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IdeTextField(
            path,
            { path = it },
            Modifier.weight(1f).testTag(AppTags.PLUGIN_PATH),
            placeholder = "Path of a .plugin file",
            monospace = true,
        )
        IdeButton(
            "Install",
            {
                background {
                    val failure = plugins.install(File(path.trim()))
                    onError(failure.orEmpty())
                    if (failure == null) path = ""
                }
            },
            Modifier.testTag(AppTags.PLUGIN_INSTALL),
            primary = true,
            enabled = path.isNotBlank(),
        )
    }
}
