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
 * The settings a session of this agent working in [directory] offers: those of its latest new session, or those of
 * a session started to read them, then deleted (or closed) so that the history does not keep it.
 */
internal suspend fun AgentProcess.offeredOptions(directory: String): List<AgentOption> {
    offered?.let { return it }
    val probe = newSession(directory, Unheard)
    val deleted = runCatching { deleteSession(probe.id) }.getOrDefault(false)
    if (!deleted) closeSession(probe.id)
    return probe.options.orEmpty()
}

// The listener of a session nobody prompts.
private object Unheard : SessionListener {
    override fun onUpdate(update: JsonObject) = Unit

    override suspend fun requestPermission(params: JsonObject): String? = null

    override fun directory(): String? = null

    override fun onTerminal(terminal: AgentTerminal) = Unit
}
