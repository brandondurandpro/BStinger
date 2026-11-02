package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.CodeLensAction
import org.example.kotlineditor.model.CodeLensInfo
import org.example.kotlineditor.model.CodeLensesResult
import org.example.kotlineditor.model.FeatureStatus

private const val LOCATIONS = "locations"

/** A code lenses answer. */
fun CodeLensesResult.toJson(): JsonObject =
    buildJsonObject {
        put("status", status.name)
        put("token", token)
        put("lenses", JsonArray(lenses.map { it.toJson() }))
    }

private fun CodeLensInfo.toJson(): JsonObject =
    buildJsonObject {
        put("range", range.toJson())
        put("title", title)
        put("action", action.name)
        if (locations.isNotEmpty()) put(LOCATIONS, JsonArray(locations.map { it.toJson() }))
    }

/** Reads a code lenses answer. */
fun JsonElement.codeLensesResult(): CodeLensesResult {
    val fields = jsonObject
    val name = fields.string("status")
    val status = FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
    val lenses = fields.getValue("lenses").jsonArray.map { it.jsonObject.codeLens() }
    return CodeLensesResult(status, fields.string("token"), lenses)
}

private fun JsonObject.codeLens(): CodeLensInfo {
    val actionName = string("action")
    val action = CodeLensAction.entries.firstOrNull { it.name == actionName } ?: CodeLensAction.None
    val locations = (get(LOCATIONS) as? JsonArray).orEmpty().map { it.jsonObject.sourceLocation() }
    return CodeLensInfo(getValue("range").textRange(), string("title"), action, locations)
}
