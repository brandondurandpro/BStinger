package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.example.kotlineditor.model.SemanticToken

private const val NUMBERS_PER_TOKEN = 5
private const val TYPE_INDEX = 3
private const val MODIFIERS_INDEX = 4

/** The token types and modifiers the IDE asks for and colors, in the order of the LSP specification. */
val SEMANTIC_TOKEN_TYPES =
    listOf(
        "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter", "variable",
        "property", "enumMember", "event", "function", "method", "macro", "keyword", "modifier", "comment",
        "string", "number", "regexp", "operator", "decorator",
    )

/** The token modifiers of the LSP specification. */
val SEMANTIC_TOKEN_MODIFIERS =
    listOf(
        "declaration", "definition", "readonly", "static", "deprecated", "abstract", "async", "modification",
        "documentation", "defaultLibrary",
    )

/** The names of the types and modifiers a server's `semanticTokensProvider.legend` gives to its numbers. */
data class SemanticLegend(
    /** The type names, by number. */
    val types: List<String>,
    /** The modifier names, by bit. */
    val modifiers: List<String>,
)

/** The legend of a `semanticTokensProvider` capability; empty when it has none. */
fun semanticLegendOf(provider: JsonElement?): SemanticLegend {
    val legend = (provider as? JsonObject)?.obj("legend")
    val types = legend?.array("tokenTypes").strings()
    val modifiers = legend?.array("tokenModifiers").strings()
    return SemanticLegend(types, modifiers)
}

private fun JsonArray?.strings(): List<String> = orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() }

/**
 * The tokens of a semanticTokens answer, `{data: [...]}` in the relative format: per token, its line as a delta to
 * the previous one, its column as a delta when on the same line, its length, its type and its modifier bits, named
 * by [legend]. Tokens of an unknown type are left out.
 */
fun semanticTokensOf(answer: JsonElement?, legend: SemanticLegend): List<SemanticToken> {
    val data = (answer as? JsonObject)?.array("data").orEmpty().map { (it as? JsonPrimitive)?.intOrNull ?: 0 }
    val tokens = ArrayList<SemanticToken>(data.size / NUMBERS_PER_TOKEN)
    var line = 0
    var column = 0
    for (numbers in data.chunked(NUMBERS_PER_TOKEN)) {
        if (numbers.size == NUMBERS_PER_TOKEN) {
            val deltaLine = numbers[0]
            column = if (deltaLine == 0) column + numbers[1] else numbers[1]
            line += deltaLine
            val name = legend.types.getOrNull(numbers[TYPE_INDEX])
            val bits = numbers[MODIFIERS_INDEX]
            val modifiers = legend.modifiers.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet()
            name?.let { tokens += SemanticToken(line, column, numbers[2], it, modifiers) }
        }
    }
    return tokens
}
