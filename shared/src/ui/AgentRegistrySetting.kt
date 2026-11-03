package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.DEFAULT_AGENT

private val LIST_HEIGHT = 260.dp

/**
 * The agents of the ACP registry, as the server reads it: choosing one clears the command line, since a command line
 * wins over it. The agents the server cannot run show why, and cannot be chosen.
 */
@Composable
internal fun AgentRegistryControl(app: AppState) {
    var entries by remember { mutableStateOf<List<AgentRegistryEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val agents = app.services.agents
    LaunchedEffect(agents) {
        runCatching { agents.registry() }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "unknown error" }
    }
    val chosen = app.agentRegistry.ifBlank { DEFAULT_AGENT }.takeIf { app.agentCommand.isBlank() }
    val listed = entries
    when {
        error != null -> {
            Label("The registry could not be read: $error", color = IdeColors.Error, fontSize = 12.sp)
        }

        listed == null -> {
            Label("Reading the registry…", color = IdeColors.TextSecondary, fontSize = 12.sp)
        }

        else -> {
            AgentList(listed, chosen) { id ->
                app.agentRegistry = id
                app.agentCommand = ""
            }
        }
    }
}

@Composable
private fun AgentList(entries: List<AgentRegistryEntry>, chosen: String?, choose: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT).testTag(AppTags.SETTINGS_AGENTS)) {
        items(entries, key = { it.id }) { entry -> AgentRow(entry, entry.id == chosen) { choose(entry.id) } }
    }
}

@Composable
private fun AgentRow(entry: AgentRegistryEntry, selected: Boolean, choose: () -> Unit) {
    val available = entry.unavailable == null
    val background = if (selected) IdeColors.Selection else Color.Transparent
    val text = if (available) IdeColors.Text else IdeColors.TextSecondary
    Column(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .clickable(enabled = available, onClick = choose)
            .testTag(AppTags.settingsAgent(entry.id))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Label("${entry.name} ${entry.version}", color = text, fontWeight = FontWeight.Medium)
        Label(entry.unavailable ?: entry.description, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
    }
}
