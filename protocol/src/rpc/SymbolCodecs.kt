package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.OutlineResult
import org.example.kotlineditor.model.WorkspaceSymbol
import org.example.kotlineditor.model.WorkspaceSymbolsResult

private const val STATUS = "status"

/** An outline answer. */
fun OutlineResult.toJson(): JsonObject =
    buildJsonObject {
        put(STATUS, status.name)
        put("declarations", JsonArray(declarations.map { it.toJson() }))
    }

/** Reads an outline answer. */
fun JsonElement.outlineResult(): OutlineResult {
    val fields = jsonObject
    val declarations = fields.getValue("declarations").jsonArray.map { it.declaration() }
    return OutlineResult(fields.featureStatus(), declarations)
}

/** A workspace symbols answer. */
fun WorkspaceSymbolsResult.toJson(): JsonObject =
    buildJsonObject {
        put(STATUS, status.name)
        val encoded =
            symbols.map { symbol ->
                buildJsonObject {
                    put("path", symbol.path)
                    put("declaration", symbol.declaration.toJson())
                    symbol.container?.let { put("container", it) }
                }
            }
        put("symbols", JsonArray(encoded))
    }

/** Reads a workspace symbols answer. */
fun JsonElement.workspaceSymbolsResult(): WorkspaceSymbolsResult {
    val fields = jsonObject
    val symbols =
        fields.getValue("symbols").jsonArray.map { element ->
            val symbol = element.jsonObject
            val declaration = symbol.getValue("declaration").declaration()
            WorkspaceSymbol(symbol.string("path"), declaration, symbol.stringOrNull("container"))
        }
    return WorkspaceSymbolsResult(fields.featureStatus(), symbols)
}

private fun JsonObject.featureStatus(): FeatureStatus {
    val name = string(STATUS)
    return FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
}
