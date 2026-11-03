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
import org.example.kotlineditor.model.SemanticToken
import org.example.kotlineditor.model.SemanticTokensResult

private const val TYPES = "types"
private const val MODIFIERS = "modifiers"
private const val FIELDS = 5
private const val TYPE_INDEX = 3
private const val MODIFIERS_INDEX = 4

/**
 * A semantic tokens answer: its type and modifier names once, then each token as five numbers, `line, column,
 * length, type index, modifier bits`, flattened (thousands of tokens per file).
 */
fun SemanticTokensResult.toJson(): JsonObject {
    val types = tokens.map { it.type }.distinct()
    val modifiers = tokens.flatMap { it.modifiers }.distinct()
    val numbers =
        tokens.flatMap { token ->
            val bits = token.modifiers.sumOf { 1 shl modifiers.indexOf(it) }
            listOf(token.line, token.column, token.length, types.indexOf(token.type), bits)
        }
    return buildJsonObject {
        put("status", status.name)
        put(TYPES, JsonArray(types.map(::JsonPrimitive)))
        put(MODIFIERS, JsonArray(modifiers.map(::JsonPrimitive)))
        put("data", JsonArray(numbers.map(::JsonPrimitive)))
    }
}

/** Reads a semantic tokens answer. */
fun JsonElement.semanticTokensResult(): SemanticTokensResult {
    val fields = jsonObject
    val name = fields.getValue("status").jsonPrimitive.content
    val status = FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
    val types = fields.getValue(TYPES).jsonArray.map { it.jsonPrimitive.content }
    val modifiers = fields.getValue(MODIFIERS).jsonArray.map { it.jsonPrimitive.content }
    val numbers = fields.getValue("data").jsonArray.map { it.jsonPrimitive.int }
    val tokens =
        numbers.chunked(FIELDS).filter { it.size == FIELDS }.map { numbersOfToken ->
            val bits = numbersOfToken[MODIFIERS_INDEX]
            val set = modifiers.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet()
            val type = types.getOrElse(numbersOfToken[TYPE_INDEX]) { "" }
            SemanticToken(numbersOfToken[0], numbersOfToken[1], numbersOfToken[2], type, set)
        }
    return SemanticTokensResult(status, tokens)
}
