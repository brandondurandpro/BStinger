package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.CatalogSkill
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.rpc.teamsOfText
import org.example.kotlineditor.rpc.teamsToText
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** A team of agents as the IDE keeps it: [global] says whether it is for every project or for this one only. */
data class SavedTeam(val team: Team, val global: Boolean)

/**
 * The teams of agents the user made, saved like the prompt templates: those for every project under one key, those of
 * this project under a key of its own. [saved] are the preferences the last run left; a team that cannot be read is
 * ignored.
 */
@Stable
class AgentTeams internal constructor(saved: Map<String, String>, private val root: String) {
    /** The teams, in the order they are listed: the user's first, in the order they were made. */
    val teams =
        mutableStateListOf<SavedTeam>().apply {
            addAll(teamsOfText(saved[PreferenceKeys.AGENT_TEAMS]).map { SavedTeam(it, global = true) })
            addAll(teamsOfText(saved[PreferenceKeys.AGENT_TEAMS_PREFIX + root]).map { SavedTeam(it, global = false) })
        }

    /** True once the ready-made team was added, or the user already had teams: it is not added again. */
    internal var seeded: Boolean = saved[PreferenceKeys.AGENT_TEAMS_SEEDED] == "true" || teams.isNotEmpty()
        private set

    /** Records that the ready-made team was added, or is not wanted. */
    internal fun seeding() {
        seeded = true
    }

    /** The skills agents can be given, as the server listed them; null until it did (or when it could not). */
    var skills: List<AgentSkill>? by mutableStateOf(null)
        private set

    /** Why the skills could not be listed; null when they were, or are being. */
    var skillsFailure: String? by mutableStateOf(null)
        private set

    /** The skills of the popular repositories, to install. */
    val catalog = SkillCatalogState()

    /** Installs [skill] of the catalog, and lists the skills again: the agents of a team can be given it at once. */
    suspend fun installFromCatalog(choices: AgentChoices, skill: CatalogSkill) {
        if (catalog.install(choices, skill)) loadSkills(choices)
    }

    /** Saves [team] (a new one, or the one of the same id, replaced in place), for every project or this one. */
    fun save(team: Team, global: Boolean) {
        val saved = SavedTeam(team, global)
        val index = teams.indexOfFirst { it.team.id == team.id }
        if (index >= 0) teams[index] = saved else teams.add(saved)
    }

    /** Called with the id of a team once it is forgotten: the conversations that chose it settle what that means. */
    internal var onRemoved: (String) -> Unit = {}

    /** Forgets the team [id]. */
    fun remove(id: String) {
        val removed = teams.removeAll { it.team.id == id }
        if (removed) onRemoved(id)
    }

    /** A copy of the team [id] with new ids, named `"<name> copy"`, listed after it; null when there is no such team. */
    fun duplicate(id: String): SavedTeam? {
        val index = teams.indexOfFirst { it.team.id == id }
        if (index < 0) return null
        val original = teams[index]
        val copied = original.team.copiedWithNewIds()
        val copy = SavedTeam(copied, original.global)
        teams.add(index + 1, copy)
        return copy
    }

    /** The team [id], when it exists. */
    fun find(id: String): SavedTeam? = teams.firstOrNull { it.team.id == id }

    /** The preferences to save: the teams of every project, and those of this project. */
    internal fun toPreferences(): Map<String, String> {
        val everywhere = teams.filter { it.global }.map { it.team }
        val here = teams.filter { !it.global }.map { it.team }
        return mapOf(
            PreferenceKeys.AGENT_TEAMS to everywhere.teamsToText(),
            PreferenceKeys.AGENT_TEAMS_PREFIX + root to here.teamsToText(),
            PreferenceKeys.AGENT_TEAMS_SEEDED to seeded.toString(),
        )
    }

    /**
     * Asks the server for the skills of the project at [root] (and of its own data directory). A server that cannot
     * answer leaves them unknown, with the reason in [skillsFailure]: the form stays usable without them.
     */
    suspend fun loadSkills(choices: AgentChoices) {
        skillsFailure = null
        try {
            skills = choices.skills(root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            skillsFailure = failure.message ?: "unknown error"
        }
    }
}

/** A new id for a team or an agent of a team. */
internal fun newTeamId(): String = UUID.randomUUID().toString()

/** [this] with new ids for the team and its agents, and the name `"<name> copy"`. */
internal fun Team.copiedWithNewIds(): Team {
    val managerCopy = manager.copy(id = newTeamId())
    val memberCopies = members.map { it.copy(id = newTeamId()) }
    return Team(newTeamId(), "$name copy", managerCopy, memberCopies)
}

/**
 * Why a team cannot be saved, as one sentence for the form; null when it can. The team has a name, every agent (the
 * manager and each member) has a name, no two agents have the same one, and there is at least one member.
 */
internal fun teamProblem(name: String, manager: TeamAgent, members: List<TeamAgent>): String? {
    if (name.isBlank()) return "Give the team a name"
    val agents = listOf(manager) + members
    if (agents.any { it.name.isBlank() }) return "Give every agent a name"
    val names = agents.map { it.name.trim() }
    val twice = names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
    if (twice != null) return "Two agents are named \"${twice.key}\""
    if (members.isEmpty()) return "Add at least one member"
    return null
}

/** A new agent of a team, on [provider], with nothing chosen yet. */
internal fun newTeamAgent(name: String, provider: AgentCommand): TeamAgent =
    TeamAgent(newTeamId(), name, description = "", agent = provider)
