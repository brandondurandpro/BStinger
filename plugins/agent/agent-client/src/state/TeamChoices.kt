package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.ui.plural
import java.io.IOException

/**
 * What the agent of a new conversation offers to the members of a team: its models, the efforts of its default model
 * and its commands (the skills), each as a list of values; an empty list when the agent offers none.
 */
class TeamOffers(
    val models: List<AgentOptionValue>,
    val efforts: List<AgentOptionValue>,
    val skills: List<AgentOptionValue>,
)

/**
 * Asks the server what the agent of a new conversation offers, and adds what the agent offered the conversations
 * already open. A server that cannot answer gives empty lists, never an error.
 */
suspend fun AgentState.teamOffers(): TeamOffers {
    val registry = lastAgent?.let { AgentCommand(emptyList(), registry = it) }
    val command = registry ?: agentOf(Conversation(-1, "", ""))
    val agents = project.services.agents
    val offered =
        try {
            agents.options(command, project.root.path)
        } catch (_: IOException) {
            emptyList()
        }
    val commands =
        try {
            agents.commands(command)
        } catch (_: IOException) {
            emptyList()
        }
    val models = offered.filter { it.category == AgentOption.MODEL }.flatMap { it.values }
    val efforts = offered.filter { it.category == AgentOption.THOUGHT_LEVEL }.flatMap { it.values }
    val skills = commands.map { AgentOptionValue(it.name, it.name, it.description.ifBlank { null }) }
    val openModels = conversations.mapNotNull { it.model }.flatMap { it.values }
    val openEfforts = conversations.mapNotNull { it.effort }.flatMap { it.values }
    val openSkills = options.commands.map { AgentOptionValue(it.name, it.name, it.description.ifBlank { null }) }
    return TeamOffers(
        distinctByName(models + openModels),
        distinctByName(efforts + openEfforts),
        distinctByName(skills + openSkills),
    )
}

private fun distinctByName(values: List<AgentOptionValue>): List<AgentOptionValue> =
    values.distinctBy { it.name.lowercase() }

/** The members of [this] team, the manager first. */
internal fun AgentTeam.everyone(): List<TeamMember> = listOf(manager) + members

/**
 * The row of [offered] that [text] stands for: its name or value, case ignored, or (when [loose]) with the same letters
 * and digits (`opus 5.5` for `Opus-5.5`). Never a row that merely contains it: a saved `high` is not an offered `xhigh`.
 */
internal fun offeredRow(offered: List<AgentOptionValue>, text: String, loose: Boolean): AgentOptionValue? {
    if (text.isBlank()) return null
    val exact = offered.firstOrNull { it.name.equals(text, ignoreCase = true) || it.value.equals(text, ignoreCase = true) }
    if (exact != null || !loose) return exact
    val key = squashed(text)
    if (key.isEmpty()) return null
    return offered.firstOrNull { squashed(it.name) == key || squashed(it.value) == key }
}


/** The values of [candidates] the agent does not offer, each once, in order: those the menu still has to show. */
internal fun otherValues(offered: List<AgentOptionValue>, candidates: List<String>, loose: Boolean): List<String> {
    val trimmed = candidates.map { it.trim() }.filter { it.isNotEmpty() }
    val distinct = trimmed.distinctBy { it.lowercase() }
    return distinct.filter { offeredRow(offered, it, loose) == null }
}

/** [skills] with [name] added, or removed when it is there (case ignored). */
internal fun toggled(skills: List<String>, name: String): List<String> {
    val present = skills.any { it.equals(name, ignoreCase = true) }
    return if (present) skills.filterNot { it.equals(name, ignoreCase = true) } else skills + name
}

/** How a member is summed up in a list: `Sonnet 5.5 · high · 2 skills`. */
internal fun TeamMember.summary(): String {
    val parts = mutableListOf<String>()
    if (model.isNotBlank()) parts.add(model)
    if (effort.isNotBlank()) parts.add(effort)
    if (skills.isNotEmpty()) parts.add(plural(skills.size, "skill", "skills"))
    return parts.joinToString(" · ").ifEmpty { "Default settings" }
}
