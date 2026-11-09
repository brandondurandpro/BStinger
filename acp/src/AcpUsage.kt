package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.example.kotlineditor.model.AgentUsage

// The context window of a session (`usage_update`): the tokens in it, its size and what the session cost.

/** The usage in [update], null when it lacks the sizes. */
internal fun usageOf(update: JsonObject): AgentUsage? {
    val used = update["used"]?.jsonPrimitive?.longOrNull
    val size = update["size"]?.jsonPrimitive?.longOrNull
    if (used == null || size == null) return null
    val cost = update["cost"].obj()
    val amount = cost?.get("amount")?.jsonPrimitive?.doubleOrNull
    return AgentUsage(used, size, amount, cost?.text("currency"))
}
