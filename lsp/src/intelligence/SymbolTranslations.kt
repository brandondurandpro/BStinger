package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.WorkspaceSymbol
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.DeclarationKind

// LSP SymbolKind values, as File Structure and Go to Symbol show them.
private val CLASS_KINDS = setOf(5, 10, 23)
private const val INTERFACE_KIND = 11
private const val OBJECT_KIND = 19
private val FUNCTION_KINDS = setOf(6, 9, 12)
private val PROPERTY_KINDS = setOf(7, 8, 14, 22)

private const val LOCATION = "location"
private const val CHILDREN = "children"
private val BY_START = compareBy<Pair<JsonObject, TextRange?>>({ it.second?.startLine }, { it.second?.startColumn })

/**
 * The declarations of a documentSymbol answer, in document order, each nested one right after its parent with a
 * greater depth. Namespaces, variables and the other kinds File Structure does not show are left out, their
 * children kept at their depth. A flat list of SymbolInformations nests under their container by name.
 */
fun outlineOf(answer: JsonElement?): List<Declaration> {
    val found = ArrayList<Declaration>()

    fun visit(symbols: List<JsonObject>, depth: Int) {
        val sorted = symbols.map { it to nameRange(it) }.sortedWith(BY_START)
        for ((symbol, range) in sorted) {
            val declaration = declarationOf(symbol, range, depth + flatDepth(symbol))
            if (declaration != null) found += declaration
            val childDepth = if (declaration != null) depth + 1 else depth
            visit(symbol.array(CHILDREN).objects(), childDepth)
        }
    }
    visit((answer as? JsonArray).objects(), 0)
    return found
}

/** The declarations of a workspace/symbol answer (SymbolInformations or WorkspaceSymbols) in `file:` URIs. */
fun workspaceSymbolsOf(answer: JsonElement?): List<WorkspaceSymbol> =
    (answer as? JsonArray).objects().mapNotNull { symbol ->
        val location = symbol.obj(LOCATION)
        val path = location?.string("uri")?.let(::pathOfUri)
        val range = location?.get("range").textRange() ?: TextRange(0, 0, 0, 0)
        val declaration = declarationOf(symbol, range, 0)
        val container = symbol.string("containerName")?.takeIf { it.isNotBlank() }
        if (path == null || declaration == null) null else WorkspaceSymbol(path, declaration, container)
    }

private fun JsonArray?.objects(): List<JsonObject> = orEmpty().mapNotNull { it as? JsonObject }

// Where the name is: the selection range of a DocumentSymbol, else the range of a SymbolInformation's location.
private fun nameRange(symbol: JsonObject): TextRange? {
    val selection = symbol["selectionRange"].textRange()
    return selection ?: symbol.obj(LOCATION)?.get("range").textRange()
}

// A SymbolInformation of a flat list is one level under its container.
private fun flatDepth(symbol: JsonObject): Int {
    val flat = symbol.obj(LOCATION) != null && symbol[CHILDREN] == null
    val contained = !symbol.string("containerName").isNullOrBlank()
    return if (flat && contained) 1 else 0
}

private fun declarationOf(symbol: JsonObject, range: TextRange?, depth: Int): Declaration? {
    val name = symbol.string("name")?.takeIf { it.isNotBlank() }
    val kind = kindOf(symbol.int("kind"))
    val named = name != null && kind != null
    return range?.takeIf { named }?.let { Declaration(name!!, kind!!, it.startLine, it.startColumn, depth) }
}

private fun kindOf(kind: Int?): DeclarationKind? =
    when (kind) {
        in CLASS_KINDS -> DeclarationKind.Class
        INTERFACE_KIND -> DeclarationKind.Interface
        OBJECT_KIND -> DeclarationKind.Object
        in FUNCTION_KINDS -> DeclarationKind.Function
        in PROPERTY_KINDS -> DeclarationKind.Property
        else -> null
    }
