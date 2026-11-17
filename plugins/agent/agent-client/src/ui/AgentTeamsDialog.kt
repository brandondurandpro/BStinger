package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.DEFAULT_AGENT
import org.example.kotlineditor.state.SavedTeam
import org.example.kotlineditor.state.TeamDraft
import org.example.kotlineditor.state.addStarterTeam
import org.example.kotlineditor.state.registryProvider

// The share of the window the dialog takes, in width and in height.
private const val WINDOW_SHARE = 0.9f
private val LIST_WIDTH = 260.dp

/** "Teams": the teams of agents, to create, edit, duplicate and delete. Beside the button of the prompt templates. */
@Composable
internal fun TeamsButton(agent: AgentState) {
    var open by remember { mutableStateOf(false) }
    Label(
        "Teams",
        Modifier
            .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(AgentTags.AGENT_TEAMS),
        color = IdeColors.TextSecondary,
        fontSize = 13.sp,
    )
    if (open) TeamsDialog(agent) { open = false }
}

// The modal: the teams on the left, the form of the one being made or edited on the right.
@Composable
internal fun TeamsDialog(agent: AgentState, close: () -> Unit) {
    // The form of the team being edited or made; null while none is.
    var draft by remember { mutableStateOf<TeamDraft?>(null) }
    val teams = agent.teams
    val choices = agent.project.services.agents
    LaunchedEffect(choices) { teams.loadSkills(choices) }
    val providerId = agent.settingsAgentId ?: DEFAULT_AGENT
    val open = { saved: SavedTeam? -> draft = TeamDraft(saved, registryProvider(providerId)) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            val width = maxWidth * WINDOW_SHARE
            val height = maxHeight * WINDOW_SHARE
            TeamsLayout(agent, draft, Modifier.size(width, height), open, { draft = null }, close)
        }
    }
}

@Composable
private fun TeamsLayout(
    agent: AgentState,
    draft: TeamDraft?,
    modifier: Modifier,
    open: (SavedTeam?) -> Unit,
    closeForm: () -> Unit,
    close: () -> Unit,
) {
    Box(
        modifier
            .background(IdeColors.Popup, IdeShapes.ExtraLarge)
            .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
            .testTag(AgentTags.AGENT_TEAMS_DIALOG),
    ) {
        TeamsColumns(agent, draft, open, closeForm, close)
        CloseCross(close, Modifier.align(Alignment.TopEnd))
    }
}

// The cross at the top right of the window, where a window is closed from: the same as the Close button of the list.
@Composable
private fun CloseCross(close: () -> Unit, modifier: Modifier) {
    IconButton(close, modifier.padding(6.dp).testTag(AgentTags.AGENT_TEAMS_CLOSE_CROSS)) {
        Label("✕", color = IdeColors.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun TeamsColumns(
    agent: AgentState,
    draft: TeamDraft?,
    open: (SavedTeam?) -> Unit,
    closeForm: () -> Unit,
    close: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(LIST_WIDTH).fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Agent teams", fontWeight = FontWeight.SemiBold)
            IdeButton("New team", { open(null) }, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAM_NEW), primary = true)
            StarterTeamButton(agent)
            TeamList(agent, draft, open, closeForm, Modifier.weight(1f))
            IdeButton("Close", close, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAMS_CLOSE))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(IdeColors.Separator))
        Column(Modifier.weight(1f).fillMaxHeight().padding(14.dp)) {
            if (draft == null) {
                Label("Choose a team, or make a new one.", color = IdeColors.TextSecondary, fontSize = 12.sp)
            } else {
                TeamForm(agent, draft, closeForm)
            }
        }
    }
}

// "Add Haiku team": a ready-made team to try teams with, on Claude and its cheapest model, whatever agent the settings
// choose: the same team as the one every user starts with.
@Composable
private fun StarterTeamButton(agent: AgentState) {
    val scope = rememberCoroutineScope()
    val choices = agent.project.services.agents
    val directory = agent.project.root.path
    val add: () -> Unit = {
        scope.launch { agent.teams.addStarterTeam(choices, registryProvider(DEFAULT_AGENT), directory) }
    }
    IdeButton("Add Haiku team", add, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAM_STARTER))
}

// The teams, as a lazy list: hundreds of them compose only the rows shown.
@Composable
private fun TeamList(agent: AgentState, draft: TeamDraft?, open: (SavedTeam?) -> Unit, closeForm: () -> Unit, modifier: Modifier) {
    val teams = agent.teams.teams
    if (teams.isEmpty()) {
        Label(
            "No team yet: make one with New team.",
            modifier.testTag(AgentTags.AGENT_TEAMS_EMPTY),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 3,
        )
        return
    }
    val state = rememberLazyListState()
    LazyColumn(modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAMS_LIST), state) {
        items(teams, key = { it.team.id }) { saved ->
            val editing = draft != null && !draft.isNew && draft.id == saved.team.id
            TeamRow(agent, saved, editing, open, closeForm)
        }
    }
}

@Composable
private fun TeamRow(agent: AgentState, saved: SavedTeam, editing: Boolean, open: (SavedTeam?) -> Unit, closeForm: () -> Unit) {
    val team = saved.team
    val background = if (editing) IdeColors.Selection else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .background(background, IdeShapes.Medium)
            .clickable { open(saved) }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(AgentTags.agentTeam(team.id)),
    ) {
        Label(team.name, fontWeight = FontWeight.Medium)
        val scope = if (saved.global) "All projects" else "This project"
        val members = if (team.members.size == 1) "1 member" else "${team.members.size} members"
        Label("$scope · $members", color = IdeColors.TextSecondary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Edit", AgentTags.agentTeamEdit(team.id)) { open(saved) }
            TextAction("Duplicate", AgentTags.agentTeamDuplicate(team.id)) { agent.teams.duplicate(team.id) }
            ConfirmButton("Delete", AgentTags.agentTeamDelete(team.id)) {
                agent.teams.remove(team.id)
                if (editing) closeForm()
            }
        }
    }
}

// A small text button, as the one that asks to confirm looks.
@Composable
internal fun TextAction(label: String, tag: String, onClick: () -> Unit) {
    Label(
        label,
        Modifier
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}
