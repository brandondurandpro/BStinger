package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation

private val MENU_WIDTH = 320.dp

// One row of the menu (its two lines of text, centered), and how much of the registry the menu shows at once.
private val MENU_ROW_HEIGHT = 44.dp

private val MENU_HEIGHT = 300.dp

/**
 * The agent of [conversation]: a click lists the agents the server can run (its registry), each one named with its
 * version and what it does, the chosen one marked; the one the settings choose comes first, marked when the
 * conversation has no choice of its own yet. The one chosen applies to the next prompt of the conversation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentPicker(agent: AgentState, conversation: Conversation) {
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
        TooltipArea(tooltip = { Tooltip("Coding agent") }) {
            val button = buttonLabel(agent, conversation, entries)
            AgentPickerButton(button, open, onOpen = { open = true })
        }
        // Escape closes it without a choice, as every menu of the IDE.
        val escape =
            Modifier.onPreviewKeyEvent { event ->
                val closes = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                if (closes) { open = false }
                closes
            }
        val choose: (String?) -> Unit = { entry ->
            open = false
            agent.chooseAgent(conversation, entry)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).then(escape)) {
            AgentPickerMenu(agent, conversation, entries, failure, choose)
        }
    }
}

@Composable
private fun AgentPickerButton(label: String, open: Boolean, onOpen: () -> Unit) {
    Label(
        "$label ▾",
        Modifier
            .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onOpen)
            .testTag(AgentTags.AGENT_AGENT)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
        maxLines = 2,
    )
}

// The name the registry gives the agent [id] the conversation runs, when it lists it.
private fun registryAgentName(id: String?, entries: List<AgentRegistryEntry>?): String? {
    val listed = entries ?: return null
    val registryId = id ?: return null
    val found = listed.firstOrNull { it.id == registryId }
    return found?.name
}

// The label of the agent of [conversation]: the agent chosen there (its name in the registry), else the agent the
// settings choose — its id in the registry, `Settings` when it comes from a command line (what it runs shows next
// to the prompt).
private fun buttonLabel(
    agent: AgentState,
    conversation: Conversation,
    entries: List<AgentRegistryEntry>?,
): String {
    val chosen = conversation.agent
    if (chosen != null) {
        val named = registryAgentName(chosen.registry, entries)
        return named ?: chosen.registry ?: "Settings"
    }
    return agent.settingsAgentId ?: "Settings"
}

@Composable
private fun AgentPickerMenu(
    agent: AgentState,
    conversation: Conversation,
    entries: List<AgentRegistryEntry>?,
    failure: String?,
    choose: (String?) -> Unit,
) {
    if (conversation.agent != null) {
        AgentOfSettingsRow(agent, choose)
        HorizontalDivider(color = IdeColors.Separator, thickness = 1.dp)
    }
    // The registry may be longer than the menu shows: a window of fixed height on it, scrolled, with its scrollbar.
    val rows = entries?.size ?: 1
    val listHeight = min(MENU_HEIGHT.value, MENU_ROW_HEIGHT.value * rows).dp
    val scroll = rememberScrollState()
    Row(Modifier.width(MENU_WIDTH).height(listHeight)) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            for (entry in entries.orEmpty()) {
                AgentChoiceRow(conversation, entry, choose)
            }
            if (entries == null) {
                val status =
                    if (failure != null) "The registry could not be read: $failure" else "Reading the registry…"
                RegistryStatusRow(status, canClose = failure != null, choose = choose)
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.fillMaxHeight().padding(end = 2.dp),
        )
    }
}

@Composable
private fun AgentOfSettingsRow(agent: AgentState, choose: (String?) -> Unit) {
    MenuRow(
        tag = AgentTags.AGENT_AGENT_DEFAULT,
        chosen = false,
        enabled = true,
        onClick = { choose(null) },
    ) {
        Column(Modifier.weight(1f)) {
            Label("Agent of the settings")
            Label(agent.settingsAgentLabel, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AgentChoiceRow(conversation: Conversation, entry: AgentRegistryEntry, choose: (String?) -> Unit) {
    val available = entry.unavailable == null
    val chosen = conversation.agent?.registry == entry.id
    MenuRow(
        tag = AgentTags.agentAgent(entry.id),
        chosen = chosen,
        enabled = available,
        onClick = { choose(entry.id) },
    ) {
        Column(Modifier.weight(1f)) {
            val title = if (chosen) FontWeight.Medium else FontWeight.Normal
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(entry.name, fontWeight = title)
                Label(entry.version, Modifier.padding(start = 6.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
            }
            val description = entry.unavailable ?: entry.description
            Label(description, Modifier.padding(top = 1.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        if (chosen) Label("✓", fontSize = 12.sp)
    }
}

@Composable
private fun RegistryStatusRow(text: String, canClose: Boolean, choose: (String?) -> Unit) {
    MenuRow(
        tag = AgentTags.AGENT_REGISTRY,
        chosen = false,
        enabled = canClose,
        onClick = { if (canClose) choose(null) },
    ) {
        Column(Modifier.weight(1f)) {
            Label(text, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

// One row of the menu: selected or hovered it shows so, hovered with a click when [enabled].
@Composable
private fun MenuRow(tag: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            chosen -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(MENU_ROW_HEIGHT)
            .background(background)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .testTag(tag),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
