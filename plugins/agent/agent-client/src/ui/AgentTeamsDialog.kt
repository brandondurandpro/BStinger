package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AgentTeam
import org.example.kotlineditor.state.OrchestrationMode
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.TeamMember
import org.example.kotlineditor.state.TeamOffers
import org.example.kotlineditor.state.everyone
import org.example.kotlineditor.state.summary
import org.example.kotlineditor.state.teamOffers
import org.example.kotlineditor.state.toggled

private val DIALOG_WIDTH = 620.dp
private val LIST_MAX_HEIGHT = 360.dp
private val EDITOR_MAX_HEIGHT = 480.dp
private val PROMPT_HEIGHT = 130.dp
private val ICON_SLOT = 24.dp
private const val MANAGER_INDEX = 0
private const val OFFERS_PATIENCE_MILLIS = 5_000L
private const val LOCKED_ALPHA = 0.5f

// What the agent offers, as the editor shows it: [loaded] is null while the server has not answered.
private class OffersView(val loaded: TeamOffers?, val silent: Boolean)

/** "Team" in the header of the conversation list: opens the window where the teams of agents are made. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TeamsButton(agent: AgentState) {
    var open by remember { mutableStateOf(false) }
    TooltipArea(tooltip = { Tooltip("Teams of agents") }) {
        Label(
            "Team",
            Modifier
                .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(AgentTags.AGENT_TEAMS),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
    if (open) TeamsDialog(agent) { open = false }
}

// The team being edited: its name and its members, the manager first; nothing is kept until Save.
private class TeamDraft {
    var previous by mutableStateOf<String?>(null)
    var name by mutableStateOf("")
    var mode by mutableStateOf(OrchestrationMode.Agent)
    var expanded by mutableStateOf<Int?>(null)
    val members = mutableStateListOf(TeamMember("Manager"))
    private var baseline = AgentTeam("", TeamMember("Manager"))

    /** True when the draft is not what was loaded: Cancel then asks before throwing it away. */
    val dirty: Boolean get() = toTeam() != baseline

    fun load(team: AgentTeam?) {
        previous = team?.name
        name = team?.name.orEmpty()
        mode = team?.mode ?: OrchestrationMode.Agent
        members.clear()
        members.add(team?.manager ?: TeamMember("Manager"))
        members.addAll(team?.members.orEmpty())
        // A new team starts with its manager open: its name is what Save needs.
        expanded = if (team == null) MANAGER_INDEX else null
        baseline = toTeam()
    }

    fun update(index: Int, change: (TeamMember) -> TeamMember) {
        val current = members.getOrNull(index) ?: return
        members[index] = change(current)
    }

    fun toggle(index: Int) {
        expanded = if (expanded == index) null else index
    }

    fun add() {
        members.add(TeamMember(""))
        expanded = members.lastIndex
    }

    fun remove(index: Int) {
        if (index == MANAGER_INDEX) return
        members.removeAt(index)
        val open = expanded
        if (open == index) expanded = null
        if (open != null && open > index) expanded = open - 1
    }

    fun toTeam(): AgentTeam {
        val manager = members.firstOrNull() ?: TeamMember("Manager")
        return AgentTeam(name, manager, members.drop(1), mode)
    }
}

@Composable
private fun TeamsDialog(agent: AgentState, close: () -> Unit) {
    val draft = remember { TeamDraft() }
    var editing by remember { mutableStateOf(false) }
    // What the agent of a new conversation offers: null while the server is asked.
    val loaded by produceState<TeamOffers?>(null) { value = agent.teamOffers() }
    // Silent: no answer after a few seconds. The request goes on and the lists fill in if the answer comes.
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OFFERS_PATIENCE_MILLIS)
        silent = true
    }
    val offers = OffersView(loaded, silent)
    val back = { editing = false }
    val edit = { team: AgentTeam? ->
        draft.load(team)
        editing = true
    }
    // Escape and a click outside: the list closes; the editor goes back to the list unless it holds changes.
    val dismiss = {
        if (!editing) {
            close()
        } else if (!draft.dirty) {
            back()
        }
    }
    Dialog(onDismissRequest = dismiss) {
        Column(
            Modifier
                .width(DIALOG_WIDTH)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editing) TeamEditor(agent, draft, offers, back) else TeamList(agent, edit, close)
        }
    }
}

@Composable
private fun TeamList(agent: AgentState, edit: (AgentTeam?) -> Unit, close: () -> Unit) {
    Label("Teams of agents", fontWeight = FontWeight.SemiBold)
    val teams = agent.teams
    if (teams.isEmpty()) {
        NoTeam { edit(null) }
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        for (team in teams.toList()) TeamRow(team, { edit(team) }) { agent.removeTeam(team.name) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IdeButton("+ New team", { edit(null) }, Modifier.testTag(AgentTags.AGENT_TEAM_NEW))
        Box(Modifier.weight(1f))
        IdeButton("Close", close, Modifier.testTag(AgentTags.AGENT_TEAM_CLOSE), primary = true)
    }
}

@Composable
private fun NoTeam(create: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(AgentTags.AGENT_TEAMS_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("No team yet", fontWeight = FontWeight.Medium)
        Label("A manager that delegates to members, each with its model, effort and skills.", color = IdeColors.TextSecondary, fontSize = 12.sp)
        IdeButton("+ New team", create, Modifier.testTag(AgentTags.AGENT_TEAM_NEW), primary = true)
    }
}

@Composable
private fun TeamRow(team: AgentTeam, edit: () -> Unit, delete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier
                .weight(1f)
                .clip(IdeShapes.Medium)
                .clickable(onClick = edit)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(AgentTags.agentTeamEntry(team.name)),
        ) {
            Label(team.name, fontWeight = FontWeight.Medium)
            val size = plural(team.members.size + 1, "agent", "agents")
            val summary = listOf(size, team.manager.model).filter { it.isNotBlank() }.joinToString(" · ")
            val relay = if (team.mode == OrchestrationMode.Relay) listOf("IDE relay") else emptyList()
            Label((listOf(summary) + relay).joinToString(" · "), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        TipIconButton(Lucide.Pencil, "Edit team", AgentTags.agentTeamEdit(team.name), edit)
        TipConfirmButton("Delete team", AgentTags.agentTeamDelete(team.name), delete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TipIconButton(icon: LucideIcon, tip: String, tag: String, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(tip) }) {
        IconButton(onClick, Modifier.testTag(tag)) { IdeIcon(icon, size = 14.dp) }
    }
}

// A trash button that asks "Sure?" before it runs [onConfirm].
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TipConfirmButton(tip: String, tag: String, onConfirm: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(tip) }) {
        ConfirmButton(tip, tag, icon = Lucide.Trash2, onConfirm = onConfirm)
    }
}

@Composable
private fun TeamEditor(agent: AgentState, draft: TeamDraft, offers: OffersView, back: () -> Unit) {
    val title = draft.previous?.let { "Edit team · $it" } ?: "New team"
    Label(title, fontWeight = FontWeight.SemiBold)
    IdeTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAM_NAME), placeholder = "Team name")
    ModeChoice(draft.mode) { draft.mode = it }
    FieldCaption("Members")
    Column(
        Modifier.fillMaxWidth().heightIn(max = EDITOR_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        draft.members.forEachIndexed { index, member ->
            MemberRow(draft, index, member)
            if (draft.expanded == index) MemberEditor(agent, draft, index, member, offers)
        }
        if (draft.members.size == 1) {
            Label("Add the members the manager delegates to.", Modifier.padding(8.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
    IdeButton("+ Add member", { draft.add() }, Modifier.testTag(AgentTags.AGENT_TEAM_ADD_MEMBER), compact = true)
    TeamEditorActions(agent, draft, back)
}

// Who runs the delegations of the team: the agent, or the IDE with a session per member.
@Composable
private fun ModeChoice(mode: OrchestrationMode, choose: (OrchestrationMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldCaption("Orchestration")
        Row(
            Modifier
                .background(IdeColors.Editor, IdeShapes.Large)
                .border(1.dp, IdeColors.Separator, IdeShapes.Large)
                .padding(2.dp),
        ) {
            val agentSelected = mode == OrchestrationMode.Agent
            ModeButton("Agent", agentSelected, AgentTags.AGENT_TEAM_MODE_AGENT) { choose(OrchestrationMode.Agent) }
            ModeButton("IDE relay", !agentSelected, AgentTags.AGENT_TEAM_MODE_RELAY) { choose(OrchestrationMode.Relay) }
        }
    }
    FieldCaption(MODE_EXPLANATION)
}

private const val MODE_EXPLANATION =
    "Agent: the agent launches its own sub-agents. IDE relay: the IDE runs each member in its own session, " +
        "with its own model and effort."

@Composable
private fun FieldCaption(text: String) {
    Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = Int.MAX_VALUE)
}

@Composable
private fun MemberRow(draft: TeamDraft, index: Int, member: TeamMember) {
    val isManager = index == MANAGER_INDEX
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(if (isManager) "♛" else "", Modifier.width(18.dp).padding(start = 4.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(IdeShapes.Medium)
                .clickable { draft.toggle(index) }
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .testTag(AgentTags.agentTeamMember(index)),
        ) {
            Label(member.name.ifBlank { "(unnamed)" }, fontWeight = FontWeight.Medium)
            Label(member.summary(), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        TipIconButton(Lucide.Pencil, "Edit member", AgentTags.agentTeamMemberEdit(index)) { draft.toggle(index) }
        // The manager cannot be removed: its slot stays so that the columns line up.
        if (isManager) {
            Box(Modifier.width(ICON_SLOT))
        } else {
            TipConfirmButton("Remove member", AgentTags.agentTeamMemberRemove(index)) { draft.remove(index) }
        }
    }
}

@Composable
private fun MemberEditor(agent: AgentState, draft: TeamDraft, index: Int, member: TeamMember, offers: OffersView) {
    val isManager = index == MANAGER_INDEX
    val everyone = agent.teams.flatMap { it.everyone() }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelledField("Name", Modifier.weight(1f)) {
                val tag = AgentTags.AGENT_MEMBER_NAME
                IdeTextField(member.name, { value -> draft.update(index) { it.copy(name = value) } }, Modifier.fillMaxWidth().testTag(tag), placeholder = "Name")
            }
            LabelledField("Model", Modifier.weight(1f)) {
                val source = ChoiceSource(offers.loaded?.models, everyone.map { it.model }, offers.silent)
                val texts = FormMenuTexts("Search models…", "Default", "Loading the agent's models…")
                ValueSelectField(member.model, AgentTags.AGENT_MEMBER_MODEL, Modifier.fillMaxWidth()) { close ->
                    SingleChoiceMenu(AgentTags.AGENT_MEMBER_MODEL, texts, source, member.model, close) { value ->
                        draft.update(index) { it.copy(model = value) }
                    }
                }
            }
            LabelledField("Effort", Modifier.weight(1f)) {
                val source = ChoiceSource(offers.loaded?.efforts, everyone.map { it.effort }, offers.silent)
                val texts = FormMenuTexts("Search efforts…", "Default", "Loading the agent's efforts…")
                ValueSelectField(member.effort, AgentTags.AGENT_MEMBER_EFFORT, Modifier.fillMaxWidth()) { close ->
                    SingleChoiceMenu(AgentTags.AGENT_MEMBER_EFFORT, texts, source, member.effort, close) { value ->
                        draft.update(index) { it.copy(effort = value) }
                    }
                }
            }
        }
        LabelledField("Skills", Modifier.fillMaxWidth()) { SkillsField(draft, index, member, offers, everyone) }
        val placeholder = if (isManager) "System prompt: when to delegate to whom, what to control" else "System prompt"
        val prompt = AgentTags.AGENT_MEMBER_PROMPT
        TemplateTextField(member.prompt, { value -> draft.update(index) { it.copy(prompt = value) } }, Modifier.testTag(prompt), placeholder, PROMPT_HEIGHT)
    }
}

@Composable
private fun LabelledField(caption: String, modifier: Modifier, field: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldCaption(caption)
        field()
    }
}

// The menu of a field of one value: a click stores it and closes the menu; the first row stores none (the agent's default).
@Composable
private fun SingleChoiceMenu(tag: String, texts: FormMenuTexts, source: ChoiceSource, current: String, close: () -> Unit, store: (String) -> Unit) {
    val chosen = listOf(current).filter { it.isNotBlank() }
    FormChoiceMenu(
        tag,
        texts,
        source,
        chosen,
        loose = true,
        pick = { value ->
            store(value)
            close()
        },
        clear = {
            store("")
            close()
        },
    )
}

@Composable
private fun SkillsField(draft: TeamDraft, index: Int, member: TeamMember, offers: OffersView, everyone: List<TeamMember>) {
    val tag = AgentTags.AGENT_MEMBER_SKILLS
    val source = ChoiceSource(offers.loaded?.skills, everyone.flatMap { it.skills }, offers.silent)
    val texts = FormMenuTexts("Search skills…", "None", "Loading the agent's skills…")
    val remove = { skill: String -> draft.update(index) { it.copy(skills = toggled(it.skills, skill)) } }
    SkillsSelectField(member.skills, tag, Modifier.fillMaxWidth(), remove) { close ->
        // A click toggles a skill and the menu stays open, to choose several; the first row clears them all.
        FormChoiceMenu(
            tag,
            texts,
            source,
            member.skills,
            loose = false,
            pick = remove,
            clear = {
                draft.update(index) { it.copy(skills = emptyList()) }
                close()
            },
        )
    }
}

@Composable
private fun TeamEditorActions(agent: AgentState, draft: TeamDraft, back: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        // A draft with changes asks "Sure?" before it is thrown away.
        if (draft.dirty) {
            ConfirmButton("Cancel", AgentTags.AGENT_TEAM_CANCEL, confirmText = "Discard?", onConfirm = back)
        } else {
            IdeButton("Cancel", back, Modifier.testTag(AgentTags.AGENT_TEAM_CANCEL))
        }
        val managerName = draft.members.firstOrNull()?.name.orEmpty()
        val saveable = draft.name.isNotBlank() && managerName.isNotBlank()
        val save = {
            if (agent.saveTeam(draft.previous, draft.toTeam())) back()
        }
        IdeButton("Save", save, Modifier.testTag(AgentTags.AGENT_TEAM_SAVE), primary = true, enabled = saveable)
    }
}

/**
 * How the team of [conversation] is orchestrated: "Agent ▾" or "IDE relay ▾", a menu until its session starts (the
 * relay needs its tool when the session starts, so the choice is locked after).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModePicker(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    val mode = agent.modeOf(conversation)
    val locked = !agent.canChooseMode(conversation)
    val name = if (mode == OrchestrationMode.Relay) "IDE relay" else "Agent"
    val tip = if (locked) "Fixed once the conversation has started: start a new one to change it" else MODE_EXPLANATION
    Box {
        TooltipArea(tooltip = { Tooltip(tip) }) {
            Label(
                "$name ▾",
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                    .clickable(enabled = !locked) { open = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag(AgentTags.AGENT_TEAM_MODE),
                color = IdeColors.TextSecondary.copy(alpha = if (locked) LOCKED_ALPHA else 1f),
                fontSize = 12.sp,
            )
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.closesOnEscape { open = false }) {
            val choices = listOf(OrchestrationMode.Agent to "Agent", OrchestrationMode.Relay to "IDE relay")
            for ((choice, text) in choices) {
                val tag = if (choice == OrchestrationMode.Relay) AgentTags.AGENT_TEAM_MODE_RELAY else AgentTags.AGENT_TEAM_MODE_AGENT
                val mark = if (choice == mode) "  ✓" else ""
                IdeMenuItem(
                    { Label(text + mark) },
                    {
                        open = false
                        agent.chooseMode(conversation, choice)
                    },
                    Modifier.testTag(tag),
                )
            }
        }
    }
}

/**
 * The team of [conversation], among those made: its manager answers and delegates to the members. It can change
 * between two turns (the next prompt carries the briefing); while the agent works the button only says which team.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TeamPicker(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf(false) }
    val locked = conversation.busy
    val label = (conversation.team?.let { "Team: $it" } ?: "No team") + " ▾"
    val tip = if (locked) "The team can change once the agent has finished its turn" else "The team that works on this conversation"
    Box {
        TooltipArea(tooltip = { Tooltip(tip) }) {
            Label(
                label,
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                    .clickable(enabled = !locked) { open = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag(AgentTags.AGENT_TEAM),
                color = IdeColors.TextSecondary.copy(alpha = if (locked) LOCKED_ALPHA else 1f),
                fontSize = 12.sp,
            )
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.closesOnEscape { open = false }) {
            IdeMenuItem(
                { Label("No team: the agent alone") },
                {
                    open = false
                    agent.chooseTeam(conversation, null)
                },
                Modifier.testTag(AgentTags.AGENT_TEAM_NONE),
            )
            for (team in agent.teams) {
                IdeMenuItem(
                    { Label(team.name + if (team.name == conversation.team) "  ✓" else "") },
                    {
                        open = false
                        agent.chooseTeam(conversation, team.name)
                    },
                    Modifier.testTag(AgentTags.agentTeam(team.name)),
                )
            }
            IdeMenuItem(
                { Label("Make a team…", color = IdeColors.Accent) },
                {
                    open = false
                    making = true
                },
                Modifier.testTag(AgentTags.AGENT_TEAM_MAKE),
            )
        }
    }
    if (conversation.team != null) ModePicker(agent, conversation)
    if (making) TeamsDialog(agent) { making = false }
}
