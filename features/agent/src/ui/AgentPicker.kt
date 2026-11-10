package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
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
        val choose: (String?) -> Unit = { entry ->
            open = false
            agent.chooseAgent(conversation, entry)
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            val tags = RegistryTags(AgentTags.AGENT_AGENT_DEFAULT, AgentTags.AGENT_AGENT_FILTER, AgentTags::agentAgent)
            val settings = agent.settingsAgentLabel.takeIf { conversation.agent != null }
            AgentRegistryMenu(RegistryMenu(settings, conversation.agent?.registry, tags), entries, failure, choose)
        }
    }
}

/** Escape closes a menu without a choice, as every menu of the IDE. */
internal fun Modifier.closesOnEscape(close: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val closes = event.key == Key.Escape && event.type == KeyEventType.KeyDown
        if (closes) close()
        closes
    }

@Composable
private fun AgentPickerButton(label: String, open: Boolean, onOpen: () -> Unit) {
    Label(
        "$label ▾",
        Modifier
            .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
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

/** The test tags of the rows of a menu of agents: the settings' one, the filter field, a row of the registry. */
internal class RegistryTags(val default: String, val filter: String, val row: (String) -> String)

/**
 * What a menu of the agents of the registry shows: [settingsLabel], the agent of the settings, as the first row (none
 * when null), [chosenId], the agent marked as chosen (none when null), and the [tags] of its rows.
 */
internal class RegistryMenu(val settingsLabel: String?, val chosenId: String?, val tags: RegistryTags)

/**
 * The content of a menu of the agents of the registry, with the field that filters them: the picker of the agent of a
 * conversation and the choice of the agent that reviews its work share it.
 */
@Composable
internal fun AgentRegistryMenu(
    menu: RegistryMenu,
    entries: List<AgentRegistryEntry>?,
    failure: String?,
    choose: (String?) -> Unit,
) {
    // What the field above the list shows: the agents it names, the one the settings choose always standing.
    var filter by remember { mutableStateOf("") }
    val wanted = wantedAgents(entries, filter)
    AgentFilterField(filter, menu.tags.filter) { filter = it }
    if (menu.settingsLabel != null) {
        AgentOfSettingsRow(menu.settingsLabel, menu.tags.default, choose)
        HorizontalDivider(color = IdeColors.Separator, thickness = 1.dp)
    }
    // The registry may be longer than the menu shows: a window of fixed height on it, scrolled, with its scrollbar.
    val rows = when {
        wanted == null -> 1
        else -> maxOf(wanted.size, 1)
    }
    val listHeight = min(MENU_HEIGHT.value, MENU_ROW_HEIGHT.value * rows).dp
    val scroll = rememberScrollState()
    Row(Modifier.width(MENU_WIDTH).height(listHeight)) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            for (entry in wanted.orEmpty()) {
                AgentChoiceRow(menu, entry, choose)
            }
            if (wanted?.isEmpty() == true) {
                RegistryStatusRow("""No agent named "$filter".""", canClose = false, choose = choose)
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

// The agents of the registry that [filter] names — its name or its id has it, case ignored; all of them while it
// stands empty, and not read yet, the registry's turn.
private fun wantedAgents(entries: List<AgentRegistryEntry>?, filter: String): List<AgentRegistryEntry>? {
    val listed = entries ?: return null
    if (filter.isBlank()) return listed
    return listed.filter { entry -> matchesFilter(entry, filter) }
}

// The name or the id of [entry] has [filter] in it, case ignored.
private fun matchesFilter(entry: AgentRegistryEntry, filter: String): Boolean =
    entry.name.contains(filter, ignoreCase = true) || entry.id.contains(filter, ignoreCase = true)

// The field that [onChange] filters the list of agents with, focused as the menu opens: the agents of the registry
// are typed, and the field is shown when it stands empty.
@Composable
internal fun AgentFilterField(
    filter: String,
    tag: String,
    placeholder: String = "Filter the agents…",
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Input, IdeShapes.Medium)
            .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (filter.isEmpty()) Label(placeholder, color = IdeColors.TextSecondary, fontSize = 12.sp)
        BasicTextField(
            filter,
            onChange,
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(tag),
            textStyle = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.Text, fontSize = 12.sp),
            singleLine = true,
            cursorBrush = SolidColor(IdeColors.Caret),
        )
    }
}

@Composable
private fun AgentOfSettingsRow(label: String, tag: String, choose: (String?) -> Unit) {
    MenuRow(
        tag = tag,
        chosen = false,
        enabled = true,
        onClick = { choose(null) },
    ) {
        Column(Modifier.weight(1f)) {
            Label("Agent of the settings")
            Label(label, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AgentChoiceRow(menu: RegistryMenu, entry: AgentRegistryEntry, choose: (String?) -> Unit) {
    val available = entry.unavailable == null
    val chosen = menu.chosenId == entry.id
    MenuRow(
        tag = menu.tags.row(entry.id),
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
        if (chosen) CheckIcon(Modifier.size(14.dp), IdeColors.Text)
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
