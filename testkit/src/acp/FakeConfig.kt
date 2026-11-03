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
private val MODES = listOf("ask" to "Ask", "code" to "Code")

/**
 * The settings of the sessions of the [FakeAgent] (ACP session config options): its model, `fake-fast` (the default)
 * and `fake-smart` in the group Standard, `fake-max` in the group Premium, as Claude groups its models; and its mode,
 * `ask` or `code`. [extraModels] more models (`fake-extra-1`…) in the group More, for a long menu.
 */
class FakeConfig(extraModels: Int = 0) {
    private val more = (1..extraModels).map { "fake-extra-$it" to "Extra $it" }

    /** Whether [value] is one of the values of [option]. */
    fun accepts(option: String, value: String): Boolean {
        val values = if (option == MODEL) STANDARD + PREMIUM + more else MODES.takeIf { option == MODE }.orEmpty()
        return values.any { it.first == value }
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
        }

    /** Ids and defaults of the settings. */
    companion object {
        /** Id of the model setting. */
        const val MODEL = "model"

        /** Id of the mode setting. */
        const val MODE = "mode"

        /** The model of a new session. */
        const val DEFAULT_MODEL = "fake-fast"
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
