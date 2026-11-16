package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import java.io.IOException

/** What a form asks of an agent of a team: its provider is an agent of the ACP registry, named by its id. */
internal fun registryProvider(registry: String): AgentCommand = AgentCommand(emptyList(), registry = registry)

/**
 * An agent of a team while its form is open: what the user types, and the settings the provider offers. The
 * [options] are those of its provider and its model, as the server last said; null while they are not known (being
 * asked, or the provider cannot say: not installed, login needed).
 */
@Stable
class AgentDraft internal constructor(from: TeamAgent) {
    internal val id: String = from.id
    var name by mutableStateOf(from.name)
    var description by mutableStateOf(from.description)
    var systemPrompt by mutableStateOf(from.systemPrompt)

    /** The provider: an agent of the ACP registry (a saved team may hold a command line, kept as it is). */
    var provider by mutableStateOf(from.agent)
        private set

    /** The settings chosen, option id to value. A setting not chosen is the agent's default. */
    val settings = mutableStateMapOf<String, String>().apply { putAll(from.settings) }

    /** The ids of the skills chosen. */
    val skills = mutableStateListOf<String>().apply { addAll(from.skills) }

    /** What the provider offers with the model chosen; null while unknown. */
    var options: List<AgentOption>? by mutableStateOf(null)
        private set

    /** Whether the options are being asked for. */
    var loadingOptions by mutableStateOf(false)
        private set

    /** The setting of the model among [options], when the provider offers one. */
    val modelOption: AgentOption? get() = options?.firstOrNull { it.category == AgentOption.MODEL }

    /** The setting of the effort among [options], when the provider offers one for the model chosen. */
    val effortOption: AgentOption? get() = options?.firstOrNull { it.category == AgentOption.THOUGHT_LEVEL }

    /** The value chosen for the setting [option], null while it is left to the agent. */
    fun chosen(option: AgentOption): String? = settings[option.id]

    /** Chooses [value] for [option]; null leaves it to the agent. */
    fun choose(option: AgentOption, value: String?) {
        if (value == null) settings.remove(option.id) else settings[option.id] = value
    }

    /** Changes the provider: what was chosen of the previous one no longer applies. */
    fun useProvider(registry: String) {
        if (provider.registry == registry) return
        provider = registryProvider(registry)
        settings.clear()
        options = null
    }

    /** Chooses or unchooses the skill [id]. */
    fun toggleSkill(id: String) {
        if (id in skills) skills.remove(id) else skills.add(id)
    }

    /** The agent as the form describes it. */
    internal fun toAgent(): TeamAgent =
        TeamAgent(
            id = id,
            name = name.trim(),
            description = description.trim(),
            agent = provider,
            settings = settings.toMap(),
            skills = skills.toList(),
            systemPrompt = systemPrompt.trim(),
        )

    /**
     * Reads what the provider offers in [directory] with the model chosen: the settings of the provider, then, when a
     * model is chosen and is not the one it offers first, those a session has once the model is set (a model changes
     * the efforts it offers). A value chosen that the answer no longer offers is forgotten: the choice was of another
     * model. A provider that cannot answer leaves the options unknown and the choices as they are.
     */
    internal suspend fun loadOptions(choices: AgentChoices, directory: String) {
        loadingOptions = true
        try {
            val base = choices.options(provider, directory)
            val modelChoice = modelChoiceOf(base)
            val answer = if (modelChoice.isEmpty()) base else choices.options(provider, directory, modelChoice)
            options = answer
            forgetWhatIsNotOffered(answer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            options = null
        } finally {
            loadingOptions = false
        }
    }

    // The model chosen as a choice to ask the provider about: none when it is the one the provider offers first, or
    // when none is chosen or it offers none of that name (it is then forgotten, with what depends on it).
    private fun modelChoiceOf(base: List<AgentOption>): Map<String, String> {
        val offered = base.firstOrNull { it.category == AgentOption.MODEL } ?: return emptyMap()
        val model = settings[offered.id] ?: return emptyMap()
        val listed = offered.values.any { it.value == model }
        if (!listed || offered.current == model) return emptyMap()
        return mapOf(offered.id to model)
    }

    // The choices the settings [offered] do not allow: an option the model does not have, a value it does not list.
    // An answer with no setting at all is a provider that cannot say, not one that offers nothing: nothing is dropped.
    private fun forgetWhatIsNotOffered(offered: List<AgentOption>) {
        if (offered.isEmpty()) return
        for ((id, value) in settings.toMap()) {
            val option = offered.firstOrNull { it.id == id }
            val allowed = option != null && option.values.any { it.value == value }
            if (!allowed) settings.remove(id)
        }
    }
}

/** A team while its form is open. */
@Stable
class TeamDraft internal constructor(from: SavedTeam?, defaultProvider: AgentCommand) {
    internal val id: String = from?.team?.id ?: newTeamId()

    /** Whether the team is new (not yet in the list). */
    val isNew: Boolean = from == null

    var name by mutableStateOf(from?.team?.name.orEmpty())
    var global by mutableStateOf(from?.global ?: true)
    val manager = AgentDraft(from?.team?.manager ?: newTeamAgent("Manager", defaultProvider))
    val members =
        mutableStateListOf<AgentDraft>().apply {
            val saved = from?.team?.members ?: listOf(newTeamAgent("Member 1", defaultProvider))
            addAll(saved.map { AgentDraft(it) })
        }

    /** The provider a member added gets: the one of the manager. */
    private val addedProvider: AgentCommand get() = manager.provider

    /** Adds a member with a name nobody has yet. */
    fun addMember() {
        val taken = (listOf(manager) + members).map { it.name.trim() }.toSet()
        var number = members.size + 1
        while ("Member $number" in taken) number++
        members.add(AgentDraft(newTeamAgent("Member $number", addedProvider)))
    }

    /** Removes [member]. */
    fun removeMember(member: AgentDraft) {
        members.remove(member)
    }

    /** Why the form cannot be saved; null when it can. */
    val problem: String? get() = teamProblem(name, manager.toAgent(), members.map { it.toAgent() })

    /** The team as the form describes it. */
    internal fun toTeam(): Team = Team(id, name.trim(), manager.toAgent(), members.map { it.toAgent() })
}
