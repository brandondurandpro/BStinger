package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.state.AgentState

private val MENU_WIDTH = 320.dp

/** What a button that lists the agents of the registry shows: its [label], its tooltip, its tag and those of its menu. */
internal class RegistryButton(
    val label: String,
    val tooltip: String,
    val tag: String,
    val tags: RegistryTags,
    /** The agent marked as chosen in its menu, and what the button says given the registry (its name, once read). */
    val chosenId: String? = null,
    val text: (List<AgentRegistryEntry>?) -> String = { label },
    /** Whether the menu starts with the agent of the settings (the team form has none: its agents are named). */
    val settingsRow: Boolean = true,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RegistryAgentButton(agent: AgentState, button: RegistryButton, enabled: Boolean, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<AgentRegistryEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val agents = agent.project.services.agents
    LaunchedEffect(agents) {
        try {
            entries = agents.registry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: IOException) {
            failure = refused.message ?: "unknown error"
        }
    }
    Box {
        TooltipArea(tooltip = { Tooltip(button.tooltip) }) {
            Label(
                "${button.text(entries)} ▾",
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                    .clickable(enabled = enabled) { open = true }
                    .testTag(button.tag)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = if (enabled) IdeColors.TextSecondary else IdeColors.LineNumber,
                fontSize = 12.sp,
            )
        }
        val picked: (String?) -> Unit = { entry ->
            open = false
            choose(entry)
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            val settings = agent.settingsAgentLabel.takeIf { button.settingsRow }
            val menu = RegistryMenu(settings, chosenId = button.chosenId, tags = button.tags)
            AgentRegistryMenu(menu, entries, failure, picked)
        }
    }
}
