package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val STANDARD = listOf("fake-fast" to "Fast", "fake-smart" to "Smart")
private val PREMIUM = listOf("fake-max" to "Max")
private val SMART_EFFORTS = listOf("low", "medium", "high")
private val MAX_EFFORTS = SMART_EFFORTS + "max"
private val MODES = listOf("manual" to "Manual", "accept-edits" to "Accept edits", "plan" to "Plan")

/**
 * The settings of the sessions of the [FakeAgent] (ACP session config options): its model, `fake-fast` (the default)
 * and `fake-smart` in the group Standard, `fake-max` in the group Premium, as Claude groups its models; and its mode
 * of permissions, as Claude's: `manual` (it asks before it writes), `accept-edits` (it writes without asking),
 * `plan`. Like Claude's, its effort (`effort`, category `thought_level`) depends on the model: none for `fake-fast`,
 * `default`, `low`, `medium`, `high` for `fake-smart`, `max` too for `fake-max`; a model without the effort chosen
 * goes back to `default`. [extraModels] more models (`fake-extra-1`…) in the group More, for a long menu.
 */
class FakeConfig(extraModels: Int = 0) {
    private val more = (1..extraModels).map { "fake-extra-$it" to "Extra $it" }

    /** Whether [value] is one of the values of [option] in [session]. */
    fun accepts(session: FakeSession, option: String, value: String): Boolean {
        val values =
            when (option) {
                MODEL -> (STANDARD + PREMIUM + more).map { it.first }
                MODE -> MODES.map { it.first }
                EFFORT -> efforts(session.model)
                else -> emptyList()
            }
        return value in values
    }

    /** Sets [option] of [session] to [value]; a model without the effort of the session sets it back to `default`. */
    fun set(session: FakeSession, option: String, value: String) {
        when (option) {
            MODEL -> session.model = value
            MODE -> session.mode = value
            EFFORT -> session.effort = value
        }
        if (session.effort !in efforts(session.model)) session.effort = DEFAULT_EFFORT
    }

    // The efforts of [model]: none for one that does not think harder on demand.
    private fun efforts(model: String): List<String> =
        when (model) {
            "fake-smart" -> listOf(DEFAULT_EFFORT) + SMART_EFFORTS
            "fake-max" -> listOf(DEFAULT_EFFORT) + MAX_EFFORTS
            else -> emptyList()
        }

    /** The settings of [session], as `configOptions`. */
    fun options(session: FakeSession): JsonArray =
        buildJsonArray {
            add(
                select(MODEL, "Model", "model", session.model) {
                    add(group("standard", "Standard", STANDARD))
                    add(group("premium", "Premium", PREMIUM))
                    if (more.isNotEmpty()) add(group("more", "More", more))
                },
            )
            add(select(MODE, "Mode", "mode", session.mode) { MODES.forEach { add(value(it)) } })
            val efforts = efforts(session.model)
            if (efforts.isNotEmpty()) {
                val named = efforts.map { it to it.replaceFirstChar(Char::uppercase) }
                add(select(EFFORT, "Effort", "thought_level", session.effort) { named.forEach { add(value(it)) } })
            }
        }

    /** Ids and defaults of the settings. */
    companion object {
        /** Id of the model setting. */
        const val MODEL = "model"

        /** Id of the mode setting. */
        const val MODE = "mode"

        /** The model of a new session. */
        const val DEFAULT_MODEL = "fake-fast"

        /** The mode of a new session: it asks before it writes. */
        const val DEFAULT_MODE = "manual"

        /** The mode in which it writes without asking. */
        const val ACCEPT_EDITS = "accept-edits"

        /** Id of the effort setting. */
        const val EFFORT = "effort"

        /** The effort of a new session: the model's own. */
        const val DEFAULT_EFFORT = "default"
    }
}

private fun select(
    id: String,
    name: String,
    category: String,
    current: String,
    values: JsonArrayBuilder.() -> Unit,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("category", category)
        put("type", "select")
        put("currentValue", current)
        putJsonArray("options", values)
    }

private fun group(id: String, name: String, values: List<Pair<String, String>>): JsonObject =
    buildJsonObject {
        put("group", id)
        put("name", name)
        putJsonArray("options") { values.forEach { add(value(it)) } }
    }

private fun value(value: Pair<String, String>): JsonObject =
    buildJsonObject {
        put("value", value.first)
        put("name", value.second)
    }
