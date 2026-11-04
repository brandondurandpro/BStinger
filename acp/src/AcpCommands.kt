package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.AgentSlashCommand

// The slash commands an agent offers (`available_commands_update`): typed in the prompt as `/name …`, sent as text.

/** The update that lists the commands of a session. */
internal const val COMMANDS_UPDATE = "available_commands_update"

/** The commands [update] lists; null for another update. */
internal fun JsonObject.slashCommands(): List<AgentSlashCommand>? {
    if (text("sessionUpdate") != COMMANDS_UPDATE) return null
    return get("availableCommands").array().mapNotNull { it.obj()?.slashCommand() }
}

private fun JsonObject.slashCommand(): AgentSlashCommand? {
    val name = text("name")?.removePrefix("/") ?: return null
    val hint = get("input").obj()?.text("hint")
    return AgentSlashCommand(name, text("description").orEmpty(), hint)
}
