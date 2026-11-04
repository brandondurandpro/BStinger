package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue

// The settings of a session (ACP session config options): its model, its mode… The agent lists them when a session
// starts or reopens, and whenever it changes one itself (`config_option_update`); the client sets one with
// `session/set_config_option`. Only the `select` ones are read: a `boolean` one needs a capability this client does
// not announce.

/** The settings in [message] (`configOptions`); null when it does not list them. */
internal fun JsonObject?.configOptions(): List<AgentOption>? {
    val options = this?.get("configOptions") as? JsonArray ?: return null
    return options.mapNotNull { it.obj()?.configOption() }
}

private fun JsonObject.configOption(): AgentOption? {
    val id = text("id")
    val current = text("currentValue").takeIf { text("type") == "select" }
    if (id == null || current == null) return null
    val values = get("options").array().flatMap { it.obj()?.optionValues().orEmpty() }
    return AgentOption(id, text("name") ?: id, text("category"), current, values)
}

// A value, or a group of values (`{group, name, options}`), flattened in order.
private fun JsonObject.optionValues(): List<AgentOptionValue> {
    val grouped = get("options") as? JsonArray
    if (grouped != null) return grouped.mapNotNull { it.obj()?.optionValue() }
    return listOfNotNull(optionValue())
}

private fun JsonObject.optionValue(): AgentOptionValue? {
    val value = text("value") ?: return null
    return AgentOptionValue(value, text("name") ?: value, text("description"))
}

/** [options] with [value] chosen for [option]: what the agent will say once it took it. */
internal fun chosen(options: List<AgentOption>, option: String, value: String): List<AgentOption> =
    options.map { known ->
        val offered = known.values.any { it.value == value }
        if (known.id == option && offered) known.copy(current = value) else known
    }

/** Sets [option] of [session] to [value]; returns the settings as the agent then says they are. */
internal suspend fun AgentProcess.setOption(session: String, option: String, value: String): List<AgentOption>? {
    val params =
        buildJsonObject {
            put(SESSION_ID, session)
            put("configId", option)
            put("value", value)
        }
    return request("session/set_config_option", params).configOptions()
}

/**
 * The settings a session of this agent working in [directory] has once [choices] are made: those of its latest new
 * session when there is no choice, else those of a session started to read them, with the choices made (one the
 * agent refuses is left out), then deleted (or closed) so that the history does not keep it.
 */
internal suspend fun AgentProcess.offeredOptions(
    directory: String,
    choices: Map<String, String> = emptyMap(),
): List<AgentOption> {
    val known = offered.takeIf { choices.isEmpty() }
    if (known != null) return known
    val probe = newSession(directory, Unheard)
    try {
        var options = probe.options.orEmpty()
        for ((option, value) in choices) {
            val changed = runCatching { setOption(probe.id, option, value) }.getOrNull()
            options = changed ?: options
        }
        return options
    } finally {
        val deleted = runCatching { deleteSession(probe.id) }.getOrDefault(false)
        if (!deleted) closeSession(probe.id)
    }
}

// The listener of a session nobody prompts.
private object Unheard : SessionListener {
    override fun onUpdate(update: JsonObject) = Unit

    override suspend fun requestPermission(params: JsonObject): String? = null

    override fun directory(): String? = null

    override fun onTerminal(terminal: AgentTerminal) = Unit
}
