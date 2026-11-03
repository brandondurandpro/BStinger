package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// LSP SymbolKind of the declarations that get lenses.
private const val METHOD = 6
private const val INTERFACE = 11
private const val FUNCTION = 12

/**
 * The code lenses of the [FakeLanguageServer], from [FakePhpCode]: an interface implemented somewhere gets
 * `2 implementations`, which lists them through VS Code's `editor.action.showReferences`; a function or method
 * without a docblock gets `Add docblock`, left unresolved until `codeLens/resolve`, which runs `add_docblock`.
 */
internal class FakeCodeLens(private val code: FakePhpCode) {
    fun lenses(params: JsonElement?): JsonElement {
        val textDocument = (params as? JsonObject)?.get("textDocument") as? JsonObject
        val uri = (textDocument?.get("uri") as? JsonPrimitive)?.contentOrNull ?: return JsonNull
        val declarations = code.declarations.filter { it.uri == uri }
        return buildJsonArray {
            for (declaration in declarations) {
                when {
                    declaration.kind == INTERFACE -> implementationsLens(declaration)?.let { add(it) }
                    declaration.kind != METHOD && declaration.kind != FUNCTION -> Unit
                    declaration.documentation == null -> add(docblockLens(declaration))
                }
            }
        }
    }

    // The command of an `Add docblock` lens, from the place its data names.
    fun resolve(params: JsonElement?): JsonElement {
        val lens = params as? JsonObject ?: return JsonNull
        val data = lens["data"] as? JsonObject
        val uri = (data?.get("uri") as? JsonPrimitive)?.contentOrNull
        val line = (data?.get("line") as? JsonPrimitive)?.intOrNull
        if (uri == null || line == null) return lens
        val command =
            buildJsonObject {
                put("title", "Add docblock")
                put("command", "add_docblock")
                putJsonArray("arguments") {
                    add(uri)
                    add(line)
                }
            }
        return JsonObject(lens + ("command" to command))
    }

    private fun docblockLens(declaration: FakeDeclaration): JsonObject =
        buildJsonObject {
            put("range", nameRange(declaration))
            putJsonObject("data") {
                put("uri", declaration.uri)
                put("line", declaration.line)
            }
        }

    private fun implementationsLens(declaration: FakeDeclaration): JsonObject? {
        val implementations = code.implementationsOf(declaration.name)
        if (implementations.isEmpty()) return null
        val count = implementations.size
        val title = if (count == 1) "1 implementation" else "$count implementations"
        return buildJsonObject {
            put("range", nameRange(declaration))
            putJsonObject("command") {
                put("title", title)
                put("command", "editor.action.showReferences")
                putJsonArray("arguments") {
                    add(declaration.uri)
                    add(position(declaration.line, declaration.column))
                    addJsonArray {
                        implementations.forEach { implementation ->
                            addJsonObject {
                                put("uri", implementation.uri)
                                put("range", nameRange(implementation))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nameRange(declaration: FakeDeclaration): JsonObject {
        val end = declaration.column + declaration.name.length
        return buildJsonObject {
            put("start", position(declaration.line, declaration.column))
            put("end", position(declaration.line, end))
        }
    }

    private fun position(line: Int, character: Int): JsonObject =
        buildJsonObject {
            put("line", line)
            put("character", character)
        }
}
