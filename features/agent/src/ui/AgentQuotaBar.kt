package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentSlashCommand
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.QuotaLimit

private val BAR_WIDTH = 90.dp
private val BAR_HEIGHT = 6.dp
private val MENU_WIDTH = 360.dp
private val MENU_HEIGHT = 320.dp
private const val WARNING_FROM = 60
private const val DANGER_FROM = 85
private const val FULL = 100f

/**
 * Above the prompt: the limits of the plan of the agent as it last said them (one bar each, green then amber then
 * red as it fills), and the button `/ ▾` that lists the commands of the agent, such as `/usage` or `/status` which
 * tell those limits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuotaBar(agent: AgentState, conversation: Conversation, modifier: Modifier) {
    val commands = conversation.commands.ifEmpty { agent.options.commands }
    Row(modifier.testTag(AgentTags.AGENT_QUOTA), verticalAlignment = Alignment.CenterVertically) {
        CommandsButton(agent, conversation, commands)
        // The limits sit on the right, the commands button on the left.
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            conversation.quota.forEachIndexed { index, limit -> QuotaChip(limit, Modifier.testTag(AgentTags.agentQuotaLimit(index))) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuotaChip(limit: QuotaLimit, modifier: Modifier) {
    val reset = limit.reset
    TooltipArea(tooltip = { Tooltip(reset ?: limit.label) }, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(limit.label, color = IdeColors.Text, fontSize = 12.sp)
            Box(
                Modifier
                    .padding(horizontal = 8.dp)
                    .width(BAR_WIDTH)
                    .height(BAR_HEIGHT)
                    .background(IdeColors.Separator, RoundedCornerShape(3.dp)),
            ) {
                val filled = limit.used / FULL
                Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(colorOf(limit.used), RoundedCornerShape(3.dp)))
            }
            Label(limit.text, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

private fun colorOf(used: Int): Color =
    when {
        used >= DANGER_FROM -> IdeColors.Error
        used >= WARNING_FROM -> IdeColors.ProblemWarning
        else -> IdeColors.GitAdded
    }

// "/ ▾": the commands of the agent, each with what it does; one chosen is put in the prompt, to complete and send.
@Composable
private fun CommandsButton(agent: AgentState, conversation: Conversation, commands: List<AgentSlashCommand>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Label(
            "/ ▾",
            Modifier
                .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(AgentTags.AGENT_COMMANDS),
            color = IdeColors.TextSecondary,
            fontSize = 13.sp,
        )
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            var filter by remember { mutableStateOf("") }
            AgentFilterField(filter, AgentTags.AGENT_COMMANDS_FILTER, "Search commands…") { filter = it }
            val shown = commands.filter { it.matches(filter) }
            if (shown.isEmpty() && agent.templates.isEmpty()) {
                Label("""No command "$filter".""", Modifier.padding(12.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            Column(Modifier.heightIn(max = MENU_HEIGHT).verticalScroll(rememberScrollState())) {
                TemplateItems(agent, conversation, filter) { open = false }
                for (command in shown) {
                    CommandItem(command) {
                        open = false
                        conversation.draft = "/${command.name} "
                        conversation.context.focus()
                    }
                }
            }
        }
    }
}

// The name or the description has [filter] in it, case ignored; every command matches an empty filter.
private fun AgentSlashCommand.matches(filter: String): Boolean {
    val wanted = filter.trim().removePrefix("/")
    if (wanted.isEmpty()) return true
    return name.contains(wanted, ignoreCase = true) || description.contains(wanted, ignoreCase = true)
}

@Composable
private fun CommandItem(command: AgentSlashCommand, choose: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                val hint = command.hint?.let { " $it" }.orEmpty()
                Label("/${command.name}$hint")
                Label(command.description, color = IdeColors.TextSecondary, fontSize = 11.sp, maxLines = 2)
            }
        },
        onClick = choose,
        modifier = Modifier.testTag(AgentTags.agentCommand(command.name)),
    )
}
