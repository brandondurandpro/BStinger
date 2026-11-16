package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import java.io.IOException

/** The name of the ready-made team. */
const val STARTER_TEAM_NAME = "Haiku team"

// The model the ready-made team runs on, as a provider names it: the cheapest one, to try teams with.
private const val STARTER_MODEL = "haiku"

// A member of the ready-made team: its name, what the manager is told of it, and its system prompt.
private class StarterRole(val name: String, val description: String, val systemPrompt: String)

private val STARTER_MANAGER =
    StarterRole(
        "Manager",
        "Coordinates the team",
        "You coordinate the team; you do not write the code yourself unless the task is trivial. Have the Spec writer " +
            "turn the request into a short specification, the Designer settle the UI when there is one, the Dev " +
            "implement it, the Code reviewer review what the Dev did, and the Tester verify it. Send work back with " +
            "precise remarks until it is right, then answer the user with a short summary.",
    )

private val STARTER_MEMBERS =
    listOf(
        StarterRole(
            "Dev",
            "Implements the tasks: writes and changes the code",
            "You implement the task you are given, following the conventions of the code around. Keep the change " +
                "small, and report the files you changed and what is left to do.",
        ),
        StarterRole(
            "Designer",
            "Designs the UI and the UX: layout, wording, states",
            "You design the user interface for the task you are given: layout, wording, empty, loading and error " +
                "states, consistent with the existing screens. Describe the design precisely; change no code.",
        ),
        StarterRole(
            "Tester",
            "Writes and runs the tests, and verifies a change",
            "You verify the change you are given: write the missing tests, run them, and report what passes and what " +
                "fails with the output. Do not fix the code under test yourself.",
        ),
        StarterRole(
            "Code reviewer",
            "Reviews the code the Dev wrote",
            "You review the changes of the Dev: bugs, missed edge cases, duplication, needless complexity, missing " +
                "tests. Read the diff with Git, change no file, and report your findings from the most serious down.",
        ),
        StarterRole(
            "Spec writer",
            "Writes the specification of a request",
            "You turn the request you are given into a short specification: the behaviour expected, the edge cases, " +
                "what is out of scope, and how to check it is done. Read the code as needed; change no file.",
        ),
    )

/**
 * The ready-made team on [provider]: a manager, a dev, a designer, a tester, a reviewer of the dev's code and a writer
 * of specifications, each with its [settings] (the model, when the provider offers the one wanted).
 */
internal fun starterTeam(provider: AgentCommand, settings: Map<String, String> = emptyMap()): Team {
    fun agentOf(role: StarterRole) =
        TeamAgent(newTeamId(), role.name, role.description, provider, settings, systemPrompt = role.systemPrompt)
    return Team(newTeamId(), STARTER_TEAM_NAME, agentOf(STARTER_MANAGER), STARTER_MEMBERS.map(::agentOf))
}

/** The setting that chooses the Haiku model among [options], as option id to value; empty when none offers it. */
internal fun starterSettings(options: List<AgentOption>): Map<String, String> {
    val model = options.firstOrNull { it.category == AgentOption.MODEL } ?: return emptyMap()
    val haiku = model.values.firstOrNull { offered -> offered.namesStarterModel() } ?: return emptyMap()
    return mapOf(model.id to haiku.value)
}

private fun AgentOptionValue.namesStarterModel(): Boolean {
    val inName = name.contains(STARTER_MODEL, ignoreCase = true)
    val inValue = value.contains(STARTER_MODEL, ignoreCase = true)
    return inName || inValue
}

/**
 * Adds the ready-made team, for every project, on [provider] working in [directory]: on its Haiku model when the
 * server says the provider offers one, on its default model otherwise (also when the server cannot say). Returns it.
 */
suspend fun AgentTeams.addStarterTeam(choices: AgentChoices, provider: AgentCommand, directory: String): SavedTeam {
    val offered =
        try {
            choices.options(provider, directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            emptyList()
        }
    val team = starterTeam(provider, starterSettings(offered))
    save(team, global = true)
    return SavedTeam(team, global = true)
}
