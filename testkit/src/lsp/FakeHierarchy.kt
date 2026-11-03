package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// A call: a name, then its opening parenthesis.
private val CALLED = Regex("""(\w+)\s*\(""")
private val SUPERTYPES = Regex("""\b(?:extends|implements)\s+([\w\s,]+)""")
private const val FILE_KIND = 1

/**
 * The call and type hierarchies of the [FakeLanguageServer], from [FakePhpCode] like phpactor: a caller is the
 * function or method around a call, or the file for a call at its top level; the supertypes of a class are those
 * it extends and implements, its subtypes the classes that extend or implement it.
 */
internal class FakeHierarchy(private val code: FakePhpCode) {
    fun prepareCalls(params: JsonElement?): JsonElement = prepare(params, CALLABLE_KINDS)

    fun prepareTypes(params: JsonElement?): JsonElement = prepare(params, TYPE_KINDS)

    private fun prepare(params: JsonElement?, kinds: Set<Int>): JsonElement {
        val obj = params as? JsonObject
        val uri = ((obj?.get("textDocument") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        val position = obj?.get("position") as? JsonObject
        val line = position?.get("line")?.jsonPrimitive?.int ?: 0
        val column = position?.get("character")?.jsonPrimitive?.int ?: 0
        val word = uri?.let { code.wordAt(it, line, column)?.first }
        val declarations = word?.let { code.declarationsNamed(it) }.orEmpty().filter { it.kind in kinds }
        return JsonArray(declarations.take(1).map(::itemOf))
    }

    fun incomingCalls(params: JsonElement?): JsonElement {
        val target = declarationOf(params) ?: return JsonArray(emptyList())
        val calls =
            code.occurrences(target.name, target.uri).filterNot { it.uri == target.uri && it.line == target.line }
        val byCaller = calls.groupBy { call -> enclosing(call.uri, call.line) to call.uri }
        return buildJsonArray {
            byCaller.forEach { (caller, found) ->
                add(
                    buildJsonObject {
                        put("from", caller.first?.let(::itemOf) ?: fileItem(caller.second))
                        put("fromRanges", JsonArray(found.map { range(it.line, it.column, it.line, it.column + it.length) }))
                    },
                )
            }
        }
    }

    fun outgoingCalls(params: JsonElement?): JsonElement {
        val caller = declarationOf(params) ?: return JsonArray(emptyList())
        val lines = code.files[caller.uri].orEmpty()
        val calls =
            (caller.line + 1..caller.endLine).flatMap { index ->
                val text = lines.getOrNull(index).orEmpty()
                CALLED.findAll(text).map { Triple(it.groupValues[1], index, it.range.first) }.toList()
            }
        val byCallee = calls.mapNotNull { call -> callable(call.first)?.let { it to call } }.groupBy({ it.first }, { it.second })
        return buildJsonArray {
            byCallee.forEach { (callee, found) ->
                add(
                    buildJsonObject {
                        put("to", itemOf(callee))
                        put(
                            "fromRanges",
                            JsonArray(found.map { (name, line, column) -> range(line, column, line, column + name.length) }),
                        )
                    },
                )
            }
        }
    }

    fun supertypes(params: JsonElement?): JsonElement {
        val type = declarationOf(params) ?: return JsonArray(emptyList())
        val text = code.files[type.uri]?.getOrNull(type.line).orEmpty()
        val names = SUPERTYPES.findAll(text).flatMap { match -> match.groupValues[1].split(',').map { it.trim().split(' ').first() } }
        val parents = names.mapNotNull { name -> code.declarationsNamed(name).firstOrNull { it.kind in TYPE_KINDS } }
        return JsonArray(parents.toList().map(::itemOf))
    }

    fun subtypes(params: JsonElement?): JsonElement {
        val type = declarationOf(params) ?: return JsonArray(emptyList())
        return JsonArray(code.implementationsOf(type.name).map(::itemOf))
    }

    // The declaration an item of the request names.
    private fun declarationOf(params: JsonElement?): FakeDeclaration? {
        val item = (params as? JsonObject)?.get("item") as? JsonObject
        val name = (item?.get("name") as? JsonPrimitive)?.contentOrNull
        val uri = (item?.get("uri") as? JsonPrimitive)?.contentOrNull
        return code.declarations.firstOrNull { it.name == name && it.uri == uri }
    }

    private fun enclosing(uri: String, line: Int): FakeDeclaration? =
        code.declarations
            .filter { it.uri == uri && it.kind in CALLABLE_KINDS && line in it.line..it.endLine }
            .maxByOrNull { it.line }

    private fun callable(name: String): FakeDeclaration? =
        code.declarationsNamed(name).firstOrNull { it.kind in CALLABLE_KINDS }

    private fun itemOf(declaration: FakeDeclaration): JsonObject =
        buildJsonObject {
            put("name", declaration.name)
            put("kind", declaration.kind)
            declaration.parent?.let { put("detail", it) }
            put("uri", declaration.uri)
            val endColumn = code.files[declaration.uri]?.getOrNull(declaration.endLine)?.length ?: 0
            put("range", range(declaration.line, declaration.start, declaration.endLine, endColumn))
            put("selectionRange", range(declaration.line, declaration.column, declaration.line, declaration.column + declaration.name.length))
        }

    // The top level of a file, which calls like a function.
    private fun fileItem(uri: String): JsonObject =
        buildJsonObject {
            put("name", uri.substringAfterLast('/'))
            put("kind", FILE_KIND)
            put("uri", uri)
            put("range", range(0, 0, 0, 0))
            put("selectionRange", range(0, 0, 0, 0))
        }

    private fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        buildJsonObject {
            putJsonObject("start") {
                put("line", startLine)
                put("character", startColumn)
            }
            putJsonObject("end") {
                put("line", endLine)
                put("character", endColumn)
            }
        }

    private companion object {
        val CALLABLE_KINDS = setOf(FakePhpCode.METHOD, FakePhpCode.FUNCTION_KIND)
        val TYPE_KINDS = setOf(FakePhpCode.CLASS, FakePhpCode.INTERFACE, FakePhpCode.ENUM)
    }
}
