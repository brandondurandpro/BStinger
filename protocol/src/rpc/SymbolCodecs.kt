package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FoldingRangesResult
import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.InlayHintKind
import org.example.kotlineditor.model.InlayHintsResult
import org.example.kotlineditor.model.OutlineResult
import org.example.kotlineditor.model.SelectionRangesResult
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

/** A selection ranges answer. */
fun SelectionRangesResult.toJson(): JsonObject =
    buildJsonObject {
        put(STATUS, status.name)
        put("ranges", JsonArray(ranges.map { it.toJson() }))
    }

/** Reads a selection ranges answer. */
fun JsonElement.selectionRangesResult(): SelectionRangesResult {
    val fields = jsonObject
    val ranges = fields.getValue("ranges").jsonArray.map { it.textRange() }
    return SelectionRangesResult(fields.featureStatus(), ranges)
}

/** A folding ranges answer: each region as [start, end]. */
fun FoldingRangesResult.toJson(): JsonObject =
    buildJsonObject {
        put(STATUS, status.name)
        put(
            "regions",
            JsonArray(regions.map { (start, end) -> JsonArray(listOf(JsonPrimitive(start), JsonPrimitive(end))) }),
        )
    }

/** Reads a folding ranges answer. */
fun JsonElement.foldingRangesResult(): FoldingRangesResult {
    val fields = jsonObject
    val regions =
        fields.getValue("regions").jsonArray.associate { region ->
            val bounds = region.jsonArray
            bounds[0].jsonPrimitive.int to bounds[1].jsonPrimitive.int
        }
    return FoldingRangesResult(fields.featureStatus(), regions)
}

/** An inlay hints answer: each hint as [line, column, label, kind]. */
fun InlayHintsResult.toJson(): JsonObject =
    buildJsonObject {
        put(STATUS, status.name)
        val encoded =
            hints.map { hint ->
                val fields = listOf(JsonPrimitive(hint.line), JsonPrimitive(hint.column))
                JsonArray(fields + JsonPrimitive(hint.label) + JsonPrimitive(hint.kind.name))
            }
        put("hints", JsonArray(encoded))
    }

/** Reads an inlay hints answer. */
fun JsonElement.inlayHintsResult(): InlayHintsResult {
    val fields = jsonObject
    val hints =
        fields.getValue("hints").jsonArray.map { encoded ->
            val parts = encoded.jsonArray
            val kind = InlayHintKind.valueOf(parts[3].jsonPrimitive.content)
            InlayHint(parts[0].jsonPrimitive.int, parts[1].jsonPrimitive.int, parts[2].jsonPrimitive.content, kind)
        }
    return InlayHintsResult(fields.featureStatus(), hints)
}
