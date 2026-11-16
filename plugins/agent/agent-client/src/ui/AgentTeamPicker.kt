package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
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
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.SavedTeam

private val MENU_WIDTH = 320.dp

// A menu shows a few teams at once, the rest comes scrolling in place.
private val MENU_HEIGHT = 300.dp

// Every row of the list has the same height: the menu asks for the height of its content, which a lazy list can only
// answer when it has a size of its own.
private val ROW_HEIGHT = 40.dp

private const val NO_TEAM = "No team"
private const val DELETED_TEAM = "Deleted team"
private const val LOCKED_REASON = "The team is fixed once the conversation has started"

/**
 * The team of [conversation], in the prompt bar after the permissions: "No team", or the name of the team chosen. Its
 * menu lists "No team", the teams of this project (those for every project and its own), and "Manage teams…", which
 * opens the Teams window. From the first prompt on it only shows the team, and says why in a tooltip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TeamPicker(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    val locked = conversation.started || conversation.busy
    Box {
        TooltipArea(tooltip = { Tooltip(if (locked) LOCKED_REASON else "Team") }) {
            TeamPickerButton(teamLabel(conversation), open, locked) { open = true }
        }
        val menuModifier = Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }
        IdeDropdownMenu(open && !locked, onDismissRequest = { open = false }, menuModifier) {
            val choose = { id: String? ->
                open = false
                agent.chooseTeam(conversation, id)
            }
            TeamMenu(agent.teams.teams, conversation.teamId, choose) {
                open = false
                managing = true
            }
        }
    }
    if (managing) TeamsDialog(agent) { managing = false }
}

// What the button says: the team chosen, as it is now; the name of a deleted one is not known.
private fun teamLabel(conversation: Conversation): String {
    if (conversation.teamId == null) return NO_TEAM
    val team = conversation.team ?: return DELETED_TEAM
    return team.team.name
}

@Composable
private fun TeamPickerButton(label: String, open: Boolean, locked: Boolean, onOpen: () -> Unit) {
    val background = if (open) IdeColors.Selection else Color.Transparent
    Label(
        if (locked) label else "$label ▾",
        Modifier
            .background(background, IdeShapes.Medium)
            .clickable(enabled = !locked, onClick = onOpen)
            .testTag(AgentTags.AGENT_TEAM_PICKER)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
}

// "No team", the teams as a lazy list (hundreds of them compose only the rows shown), then "Manage teams…".
@Composable
private fun TeamMenu(teams: List<SavedTeam>, chosenId: String?, choose: (String?) -> Unit, manage: () -> Unit) {
    TeamChoice(NO_TEAM, null, chosenId == null, AgentTags.AGENT_TEAM_PICKER_NONE) { choose(null) }
    if (teams.isNotEmpty()) {
        // A fixed size: the menu measures the intrinsic size of its content, which a lazy list cannot answer.
        val height = minOf(MENU_HEIGHT, ROW_HEIGHT * teams.size)
        LazyColumn(Modifier.width(MENU_WIDTH).height(height).testTag(AgentTags.AGENT_TEAM_PICKER_LIST)) {
            items(teams, key = { it.team.id }) { saved ->
                val scope = if (saved.global) null else "This project"
                val tag = AgentTags.agentTeamChoice(saved.team.id)
                TeamChoice(saved.team.name, scope, chosenId == saved.team.id, tag, Modifier.height(ROW_HEIGHT)) {
                    choose(saved.team.id)
                }
            }
        }
    }
    HorizontalDivider(color = IdeColors.Separator, thickness = 1.dp)
    TeamChoice("Manage teams…", null, false, AgentTags.AGENT_TEAM_PICKER_MANAGE, onClick = manage)
}

@Composable
private fun TeamChoice(
    name: String,
    scope: String?,
    chosen: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IdeMenuItem(
        text = {
            Column {
                val mark = if (chosen) "✓  " else "     "
                Label(mark + name, fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
                if (scope != null) Label("     $scope", color = IdeColors.TextSecondary, fontSize = 11.sp)
            }
        },
        onClick = onClick,
        modifier = modifier.testTag(tag),
    )
}

/** Says that the team of a started conversation was deleted: its prompts do not go to another agent meanwhile. */
@Composable
internal fun TeamMissingNotice(modifier: Modifier) {
    Label(
        "The team of this conversation was deleted. Its prompts are not sent to another agent.",
        modifier.testTag(AgentTags.AGENT_TEAM_MISSING),
        color = IdeColors.Error,
        fontSize = 12.sp,
    )
}
