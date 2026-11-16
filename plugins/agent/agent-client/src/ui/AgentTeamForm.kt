package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.state.AgentDraft
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.TeamDraft

private val MENU_WIDTH = 360.dp
private val MENU_HEIGHT = 300.dp
private val PROMPT_HEIGHT = 90.dp

// What the dropdown of a setting offers to leave the choice to the agent.
private const val DEFAULT_VALUE = ""
private const val DEFAULT_NAME = "Default"

// The form of a team: its name and scope, the manager, the members, and Save and Cancel.
@Composable
internal fun TeamForm(agent: AgentState, draft: TeamDraft, close: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAM_FORM), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IdeTextField(
                draft.name,
                { draft.name = it },
                Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEAM_NAME),
                placeholder = "Team name",
            )
            ScopeChoice(draft.global, AgentTags.AGENT_TEAM_GLOBAL, AgentTags.AGENT_TEAM_PROJECT) { draft.global = it }
            AgentSection(agent, "Manager", AgentTags.TEAM_MANAGER, draft.manager, onRemove = null)
            draft.members.forEachIndexed { index, member ->
                val role = AgentTags.teamMember(index)
                AgentSection(agent, "Member ${index + 1}", role, member, onRemove = { draft.removeMember(member) })
            }
            IdeButton("Add member", { draft.addMember() }, Modifier.testTag(AgentTags.AGENT_TEAM_ADD_MEMBER))
        }
        TeamFormActions(agent, draft, close)
    }
}

@Composable
private fun TeamFormActions(agent: AgentState, draft: TeamDraft, close: () -> Unit) {
    val problem = draft.problem
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (problem != null) {
            Label(problem, Modifier.weight(1f).testTag(AgentTags.AGENT_TEAM_PROBLEM), color = IdeColors.Error, fontSize = 12.sp)
        } else {
            Box(Modifier.weight(1f))
        }
        IdeButton("Cancel", close, Modifier.testTag(AgentTags.AGENT_TEAM_CANCEL))
        IdeButton(
            "Save",
            {
                agent.teams.save(draft.toTeam(), draft.global)
                close()
            },
            Modifier.testTag(AgentTags.AGENT_TEAM_SAVE),
            primary = true,
            enabled = problem == null,
        )
    }
}

// One agent of the team: its name, its role, its provider, model, effort and skills, and its system prompt.
@Composable
private fun AgentSection(agent: AgentState, title: String, role: String, draft: AgentDraft, onRemove: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, IdeColors.Separator, IdeShapes.Medium)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(title, fontWeight = FontWeight.SemiBold)
            IdeTextField(
                draft.name,
                { draft.name = it },
                Modifier.weight(1f).testTag(AgentTags.agentTeamField(role, "name")),
                placeholder = "Name",
            )
            if (onRemove != null) TextAction("Remove", AgentTags.agentTeamField(role, "remove"), onRemove)
        }
        IdeTextField(
            draft.description,
            { draft.description = it },
            Modifier.fillMaxWidth().testTag(AgentTags.agentTeamField(role, "description")),
            placeholder = "Role: what this agent is for",
        )
        AgentChoices(agent, role, draft)
        MultilineField(
            draft.systemPrompt,
            { draft.systemPrompt = it },
            Modifier.testTag(AgentTags.agentTeamField(role, "prompt")),
            "System prompt",
            PROMPT_HEIGHT,
        )
    }
}

// The provider, the model and the effort of an agent, and its skills. What the provider offers is read from the server
// for its provider and model, and a late answer for a previous choice never shows: a choice cancels the one before.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentChoices(agent: AgentState, role: String, draft: AgentDraft) {
    val choices = agent.project.services.agents
    val directory = agent.project.root.path
    val modelId = draft.modelOption?.id
    val model = modelId?.let { draft.settings[it] }
    LaunchedEffect(draft.provider, model) { draft.loadOptions(choices, directory) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProviderPicker(agent, role, draft)
        SettingPicker(role, "model", "Model", draft.modelOption, draft)
        if (draft.effortOption != null) SettingPicker(role, "effort", "Effort", draft.effortOption, draft)
        SkillsPicker(agent, role, draft)
    }
}

@Composable
private fun ProviderPicker(agent: AgentState, role: String, draft: AgentDraft) {
    val provider = draft.provider.registry
    val tags =
        RegistryTags(
            AgentTags.agentTeamField(role, "provider.default"),
            AgentTags.agentTeamField(role, "provider.filter"),
        ) { id -> AgentTags.agentTeamValue(role, "provider", id) }
    val nameOf: (List<org.example.kotlineditor.model.AgentRegistryEntry>?) -> String = { entries ->
        val named = entries?.firstOrNull { it.id == provider }?.name
        named ?: provider ?: draft.provider.command.joinToString(" ")
    }
    val button =
        RegistryButton(
            label = "Provider",
            tooltip = "The agent that runs it",
            tag = AgentTags.agentTeamField(role, "provider"),
            tags = tags,
            chosenId = provider,
            text = nameOf,
            settingsRow = false,
        )
    RegistryAgentButton(agent, button, enabled = true) { entry -> if (entry != null) draft.useProvider(entry) }
}

// The model or the effort: a dropdown of the values the provider offers, "Default" to leave it to the agent. While the
// provider has not said (loading, not installed, login needed) it is a disabled "Default" and the form stays usable.
@Composable
private fun SettingPicker(role: String, field: String, name: String, option: AgentOption?, draft: AgentDraft) {
    val tag = AgentTags.agentTeamField(role, field)
    if (option == null) {
        Label(
            "$DEFAULT_NAME ▾",
            Modifier.testTag(AgentTags.agentTeamField(role, "$field.unavailable")).padding(horizontal = 6.dp, vertical = 2.dp),
            color = IdeColors.LineNumber,
            fontSize = 12.sp,
        )
        return
    }
    val chosen = draft.chosen(option) ?: DEFAULT_VALUE
    val withDefault = listOf(AgentOptionValue(DEFAULT_VALUE, DEFAULT_NAME)) + option.values
    val shown = option.copy(name = name, current = chosen, values = withDefault)
    val valueTag = { value: String -> AgentTags.agentTeamValue(role, field, value.ifEmpty { DEFAULT_VALUE }) }
    OptionPicker(shown, tag, valueTag) { value -> draft.choose(option, value.ifEmpty { null }) }
}

// "Skills (2) ▾": a menu of the skills the server found, to check; it stays open while several are checked.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillsPicker(agent: AgentState, role: String, draft: AgentDraft) {
    var open by remember { mutableStateOf(false) }
    val tag = AgentTags.agentTeamField(role, "skills")
    val count = draft.skills.size
    val label = if (count == 0) "Skills ▾" else "Skills ($count) ▾"
    Box {
        TooltipArea(tooltip = { Tooltip("SKILL.md folders this agent is told to read") }) {
            Label(
                label,
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                    .clickable { open = true }
                    .testTag(tag)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            SkillsMenu(agent, role, draft)
        }
    }
}

@Composable
private fun SkillsMenu(agent: AgentState, role: String, draft: AgentDraft) {
    val skills = agent.teams.skills
    val failure = agent.teams.skillsFailure
    Column(Modifier.widthIn(max = MENU_WIDTH).heightIn(max = MENU_HEIGHT).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
        if (skills == null) {
            val text = if (failure != null) "The skills could not be listed: $failure" else "Listing the skills…"
            Label(text, Modifier.padding(vertical = 8.dp), color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 3)
        } else {
            if (skills.isEmpty()) {
                Label(
                    "No skill found: a folder with a SKILL.md under .claude/skills or skills of the project, or skills of the IDE's data directory.",
                    Modifier.padding(vertical = 8.dp),
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 4,
                )
            }
            for (skill in skills) SkillRow(role, draft, skill)
            val listed = skills.map { it.id }.toSet()
            for (missing in draft.skills.filter { it !in listed }) {
                CheckRow("Not found: $missing", true, AgentTags.agentTeamValue(role, "skills", missing)) { draft.toggleSkill(missing) }
            }
        }
    }
}

@Composable
private fun SkillRow(role: String, draft: AgentDraft, skill: AgentSkill) {
    val where = if (skill.project) "Project" else "IDE"
    val text = skill.description.ifBlank { skill.id }
    CheckRow(
        "${skill.name}  ·  $where",
        skill.id in draft.skills,
        AgentTags.agentTeamValue(role, "skills", skill.id),
        description = text,
    ) { draft.toggleSkill(skill.id) }
}
