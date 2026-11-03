package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * The legend of the fake server's semantic tokens: on purpose not in the order of the LSP specification, so that a
 * client reading the numbers without the legend colors the wrong things.
 */
internal val FAKE_TOKEN_TYPES = listOf("class", "interface", "method", "function", "parameter", "variable", "property")
internal val FAKE_TOKEN_MODIFIERS = listOf("declaration", "static", "deprecated")

private val WORD = Regex("""\$?[A-Za-z_][A-Za-z0-9_]*""")

/** A classified word, before encoding. */
private data class Token(val line: Int, val column: Int, val length: Int, val type: String, val modifiers: Set<String>)

/**
 * The semantic tokens of the [FakeLanguageServer], from [FakePhpCode] like intelephense: declared types and
 * functions (declarations marked), methods after `->` and static ones after `::`, the parameters of the function
 * around, other variables, properties; `@deprecated` in a docblock marks a declaration and its uses deprecated.
 */
internal class FakeSemanticTokens(private val code: FakePhpCode) {
    fun tokens(params: JsonElement?): JsonElement {
        val textDocument = (params as? JsonObject)?.get("textDocument") as? JsonObject
        val uri = (textDocument?.get("uri") as? JsonPrimitive)?.contentOrNull
        val lines = uri?.let { code.files[it] } ?: return JsonNull
        val tokens = lines.flatMapIndexed { index, text -> lineTokens(uri, index, text) }
        return buildJsonObject { putJsonArray("data") { encode(tokens).forEach { add(it) } } }
    }

    private fun lineTokens(uri: String, index: Int, text: String): List<Token> {
        val trimmed = text.trimStart()
        val comment = trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
        if (comment) return emptyList()
        val code = text.substringBefore("//")
        val parameters = parametersAround(uri, index)
        return WORD.findAll(code).mapNotNull { word -> classify(uri, index, code, word.range.first, word.value, parameters) }.toList()
    }

    @Suppress("ReturnCount")
    private fun classify(uri: String, line: Int, text: String, column: Int, word: String, parameters: Set<String>): Token? {
        if (word.startsWith("$")) {
            if (word == "\$this") return null
            val type = if (word in parameters) "parameter" else "variable"
            return Token(line, column, word.length, type, emptySet())
        }
        val before = text.substring(0, column)
        val after = text.substring(column + word.length).trimStart()
        val call = after.startsWith("(")
        val declared = code.declarations.firstOrNull { it.uri == uri && it.line == line && it.column == column }
        val known = code.declarationsNamed(word).firstOrNull()
        val deprecated = (declared ?: known)?.documentation?.contains("@deprecated") == true
        val marks = setOfNotNull("declaration".takeIf { declared != null }, "deprecated".takeIf { deprecated })
        val static = before.endsWith("::") || (declared != null && Regex("""\bstatic\b""").containsMatchIn(before))
        val modifiers = if (static) marks + "static" else marks
        val type =
            when {
                declared != null -> typeOf(declared.kind)
                before.endsWith("->") && call -> "method"
                before.endsWith("::") && call -> "method"
                before.endsWith("->") -> "property"
                known != null -> typeOf(known.kind)
                else -> null
            }
        return type?.let { Token(line, column, word.length, it, modifiers) }
    }

    private fun typeOf(kind: Int): String =
        when (kind) {
            FakePhpCode.INTERFACE -> "interface"
            FakePhpCode.METHOD -> "method"
            FakePhpCode.FUNCTION_KIND -> "function"
            else -> "class"
        }

    // The `$names` of the parameters of the function or method whose body holds [line].
    private fun parametersAround(uri: String, line: Int): Set<String> {
        val function =
            code.declarations
                .filter { it.uri == uri && it.kind != FakePhpCode.CLASS && it.kind != FakePhpCode.INTERFACE }
                .filter { line in it.line..it.endLine }
                .maxByOrNull { it.line }
        val signature = function?.signature?.substringAfter('(', "")?.substringBefore(')').orEmpty()
        return Regex("""\$\w+""").findAll(signature).map { it.value }.toSet()
    }

    // The relative format: line delta, column (delta on the same line), length, type index, modifier bits.
    private fun encode(tokens: List<Token>): List<Int> {
        val numbers = ArrayList<Int>()
        var line = 0
        var column = 0
        for (token in tokens.sortedWith(compareBy({ it.line }, { it.column }))) {
            val deltaColumn = if (token.line == line) token.column - column else token.column
            val bits = token.modifiers.sumOf { 1 shl FAKE_TOKEN_MODIFIERS.indexOf(it) }
            numbers += listOf(token.line - line, deltaColumn, token.length, FAKE_TOKEN_TYPES.indexOf(token.type), bits)
            line = token.line
            column = token.column
        }
        return numbers
    }
}

/** The `semanticTokensProvider` capability of the fake server. */
internal fun fakeSemanticTokensProvider(): JsonObject =
    buildJsonObject {
        put(
            "legend",
            buildJsonObject {
                put("tokenTypes", JsonArray(FAKE_TOKEN_TYPES.map { JsonPrimitive(it) }))
                put("tokenModifiers", JsonArray(FAKE_TOKEN_MODIFIERS.map { JsonPrimitive(it) }))
            },
        )
        put("full", true)
        put("range", true)
    }
