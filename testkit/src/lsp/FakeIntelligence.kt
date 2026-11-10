package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

import kotlinx.serialization.json.putJsonObject

// A call: a name, then its opening parenthesis.
private val CALL = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

// A variable assigned a new object: `$greeter = new Greeter(`.
private val CREATED = Regex("""(\$[A-Za-z_][A-Za-z0-9_]*)\s*=\s*new\s+([A-Za-z_][A-Za-z0-9_]*)""")
private const val CLASS_KIND = 5
private const val TYPE_HINT = 1

/**
 * The answers of the [FakeLanguageServer] to the code intelligence requests, computed from [FakePhpCode] like
 * phpactor answers them: Markdown hovers with the docblock and the signature, a definition range covering the whole
 * declaration, references by whole word, a `documentChanges` rename, code actions with an edit or with a command.
 */
internal class FakeIntelligence(private val code: FakePhpCode) {
    fun hover(params: JsonElement?): JsonElement {
        val (uri, word, start, line) = wordOf(params) ?: return JsonNull
        val markdown = markdownOf(word, uri) ?: return JsonNull
        return buildJsonObject {
            putJsonObject("contents") {
                put("kind", "markdown")
                put("value", markdown)
            }
            put("range", range(line, start, line, start + word.length))
        }
    }

    // The item with the documentation of the function it names, like phpactor's resolve.
    fun resolveCompletion(params: JsonElement?): JsonElement {
        val item = params as? JsonObject ?: return JsonNull
        val label = (item["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val markdown = if (label.startsWith("$")) null else markdownOf(label, "")
        return JsonObject(
            item +
                listOfNotNull(
                    ("detail" to JsonPrimitive("resolved type of $label")).takeIf { "detail" !in item },
                    markdown?.let {
                        "documentation" to
                            buildJsonObject {
                                put("kind", "markdown")
                                put("value", it)
                            }
                    },
                ),
        )
    }

    private fun markdownOf(word: String, uri: String): String? {
        val declaration = code.declarationsNamed(word).firstOrNull()
        val builtIn = FakePhpCode.BUILT_INS[word]
        val type = if (word.startsWith("$")) code.typeOf(word, uri) else null
        return when {
            declaration != null -> declarationMarkdown(declaration)
            builtIn != null -> "### $word\n\n${builtIn.second}\n\n```php\n<?php ${builtIn.first}\n```"
            type != null -> "```php\n<?php $word: $type\n```"
            word.startsWith("$") && code.assignmentOf(word, uri) != null -> "```php\n<?php $word: mixed\n```"
            else -> null
        }
    }

    private fun declarationMarkdown(declaration: FakeDeclaration): String {
        val title = declaration.parent?.let { "$it # ${declaration.name}" } ?: declaration.name
        val documentation = declaration.documentation?.let { docblockMarkdown(it) + "\n\n" }.orEmpty()
        return "### $title\n\n$documentation```php\n<?php ${declaration.signature}\n```"
    }

    // `@param string $name Who` becomes a list item, `@return` a bold line, the rest a paragraph.
    private fun docblockMarkdown(docblock: String): String {
        val summary = docblock.lines().takeWhile { !it.startsWith("@") }.joinToString("\n").trim()
        val params =
            docblock.lines().filter { it.startsWith("@param") }.map { tag ->
                val parts = tag.removePrefix("@param").trim().split(Regex("\\s+"), limit = 3)
                "- **${parts.getOrElse(1) { "" }}**: *${parts.getOrElse(0) { "" }}* ${parts.getOrElse(2) { "" }}".trimEnd()
            }
        val returns =
            docblock.lines().firstOrNull { it.startsWith("@return") }?.let { tag ->
                val parts = tag.removePrefix("@return").trim().split(Regex("\\s+"), limit = 2)
                "**Return** *${parts.getOrElse(0) { "" }}*: ${parts.getOrElse(1) { "" }}".trimEnd()
            }
        return listOfNotNull(summary.takeIf { it.isNotEmpty() }, params.joinToString("\n").takeIf { it.isNotEmpty() }, returns)
            .joinToString("\n\n")
    }

    fun definition(params: JsonElement?): JsonElement {
        val (uri, word, _, _) = wordOf(params) ?: return JsonNull
        if (word.startsWith("$")) {
            val assignment = code.assignmentOf(word, uri) ?: return JsonNull
            return location(uri, assignment.line, assignment.column, assignment.line, assignment.column + assignment.length)
        }
        val declarations = code.declarationsNamed(word)
        val locations = declarations.map { location(it.uri, it.line, it.start, it.endLine, code.files[it.uri]?.getOrNull(it.endLine)?.length ?: 0) }
        // phpactor answers one Location, other servers an array: both are exercised.
        return when (locations.size) {
            0 -> JsonNull
            1 -> locations.first()
            else -> JsonArray(locations)
        }
    }

    fun typeDefinition(params: JsonElement?): JsonElement {
        val (uri, word, _, _) = wordOf(params) ?: return JsonNull
        val type = if (word.startsWith("$")) code.typeOf(word, uri) else word
        val declaration = type?.let { code.declarationsNamed(it).firstOrNull { d -> d.kind != FakePhpCode.METHOD } } ?: return JsonNull
        return JsonArray(listOf(location(declaration.uri, declaration.line, declaration.column, declaration.line, declaration.column + declaration.name.length)))
    }

    fun implementation(params: JsonElement?): JsonElement {
        val (_, word, _, _) = wordOf(params) ?: return JsonArray(emptyList())
        return JsonArray(
            code.implementationsOf(word).map {
                location(it.uri, it.line, it.column, it.line, it.column + it.name.length)
            },
        )
    }

    fun references(params: JsonElement?): JsonElement {
        val (uri, word, _, _) = wordOf(params) ?: return JsonArray(emptyList())
        val context = params?.jsonObject?.get("context")?.jsonObject
        val includeDeclaration = context?.get("includeDeclaration")?.jsonPrimitive?.boolean ?: true
        val declarations = code.declarationsNamed(word).map { Triple(it.uri, it.line, it.column) }.toSet()
        val occurrences =
            code.occurrences(word, uri).filter { includeDeclaration || Triple(it.uri, it.line, it.column) !in declarations }
        return JsonArray(occurrences.map { location(it.uri, it.line, it.column, it.line, it.column + it.length) })
    }

    fun highlights(params: JsonElement?): JsonElement {
        val (uri, word, _, _) = wordOf(params) ?: return JsonArray(emptyList())
        val lines = code.files[uri].orEmpty()
        return buildJsonArray {
            code.occurrences(word, uri).filter { it.uri == uri }.forEach { occurrence ->
                val after = lines[occurrence.line].substring(occurrence.column + occurrence.length)
                val isWrite = Regex("""^\s*=[^=]""").containsMatchIn(after) || code.declarationsNamed(word).any { it.line == occurrence.line && it.uri == uri }
                addJsonObject {
                    put("range", range(occurrence.line, occurrence.column, occurrence.line, occurrence.column + occurrence.length))
                    put("kind", if (isWrite) WRITE else READ)
                }
            }
        }
    }

    fun documentSymbols(params: JsonElement?): JsonElement {
        val uri = uriOf(params) ?: return JsonArray(emptyList())
        val lines = code.files[uri].orEmpty()
        val declarations = code.declarations.filter { it.uri == uri }
        fun symbol(declaration: FakeDeclaration, children: List<FakeDeclaration>) =
            buildJsonObject {
                put("name", declaration.name)
                put("kind", declaration.kind)
                put("range", range(declaration.line, declaration.start, declaration.endLine, lines.getOrNull(declaration.endLine)?.length ?: 0))
                put("selectionRange", range(declaration.line, declaration.column, declaration.line, declaration.column + declaration.name.length))
                putJsonArray("children") { children.forEach { add(symbolOf(it, lines)) } }
            }
        return buildJsonArray {
            declarations.filter { it.parent == null }.forEach { top ->
                add(symbol(top, declarations.filter { it.parent == top.name }))
            }
        }
    }

    /**
     * Like phpactor: `name:` before each argument of a call of a known function or method (kind 2, padded right);
     * with [types], `: Greeter` after a variable assigned a `new Greeter`, and `class Greeter` after the brace that
     * closes the class (kind 1).
     */
    fun inlayHints(params: JsonElement?, types: Boolean = false): JsonElement {
        val uri = (params as? JsonObject)?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
        val lines = uri?.let { code.files[it] } ?: return JsonNull
        return buildJsonArray {
            if (types) typeHints(uri, lines).forEach(::add)
            lines.forEachIndexed { index, line ->
                CALL.findAll(line).forEach { call ->
                    val name = call.groupValues[1]
                    val declared = line.substring(0, call.range.first).trimEnd().endsWith("function")
                    val parameters = code.declarationsNamed(name).firstOrNull()?.let(::parametersOf).orEmpty()
                    val open = call.range.last
                    if (!declared) {
                        argumentStarts(line, open).zip(parameters).forEach { (column, parameter) ->
                            add(
                                buildJsonObject {
                                    putJsonObject("position") {
                                        put("line", index)
                                        put("character", column)
                                    }
                                    put("label", "$parameter:")
                                    put("kind", 2)
                                    put("paddingRight", true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // phpactor's type hints: after each variable assigned a new object, and after the brace closing each class.
    private fun typeHints(uri: String, lines: List<String>): List<JsonObject> {
        val variables =
            lines.flatMapIndexed { index, line ->
                CREATED.findAll(line).map { found ->
                    val variable = found.groups[1]?.range ?: found.range
                    typeHint(index, variable.last + 1, ": ${found.groupValues[2]}", padded = false)
                }.toList()
            }
        val classes =
            code.declarations.filter { it.uri == uri && it.kind == CLASS_KIND }.mapNotNull { declaration ->
                val closing = lines.getOrNull(declaration.endLine)?.lastIndexOf('}') ?: -1
                closing.takeIf { it >= 0 }?.let { typeHint(declaration.endLine, it + 1, "class ${declaration.name}", padded = true) }
            }
        return variables + classes
    }

    private fun typeHint(line: Int, column: Int, label: String, padded: Boolean): JsonObject =
        buildJsonObject {
            putJsonObject("position") {
                put("line", line)
                put("character", column)
            }
            put("label", label)
            put("kind", TYPE_HINT)
            if (padded) put("paddingLeft", true)
        }

    // The names of the parameters of a declaration, without their `$`.
    private fun parametersOf(declaration: FakeDeclaration): List<String> {
        val inside = declaration.signature.substringAfter('(', "").substringBefore(')')
        return inside.split(',').mapNotNull { parameter -> parameter.trim().split(' ').lastOrNull { it.startsWith('$') } }
            .map { it.removePrefix("$") }
    }

    // Where each argument of the call whose `(` is at [open] starts, up to its `)` on the same line.
    private fun argumentStarts(line: String, open: Int): List<Int> {
        val starts = ArrayList<Int>()
        var depth = 0
        var expecting = true
        var index = open + 1
        while (index < line.length && depth >= 0) {
            val c = line[index]
            when {
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == ',' && depth == 0 -> expecting = true
            }
            val startsArgument = expecting && depth >= 0 && !c.isWhitespace() && c != ',' && c != ')'
            if (startsArgument) {
                starts += index
                expecting = false
            }
            index++
        }
        return starts
    }

    // Like phpactor: each declaration with a body, each docblock, and each run of `use` lines (kind imports).
    fun foldingRanges(params: JsonElement?): JsonElement {
        val uri = (params as? JsonObject)?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
        val lines = uri?.let { code.files[it] } ?: return JsonNull
        val ranges = ArrayList<Pair<Int, Int>>()
        code.declarations.filter { it.uri == uri && it.endLine > it.line }.forEach { ranges += it.line to it.endLine }
        var docStart = -1
        var useStart = -1
        lines.forEachIndexed { index, line ->
            val text = line.trim()
            if (text.startsWith("/**")) docStart = index
            if (text.endsWith("*/") && docStart in 0 until index) ranges += docStart to index
            if (text.endsWith("*/")) docStart = -1
            val isUse = text.startsWith("use ")
            if (isUse && useStart < 0) useStart = index
            if (!isUse && useStart in 0 until index - 1) ranges += useStart to index - 1
            if (!isUse) useStart = -1
        }
        return buildJsonArray {
            ranges.forEach { (start, end) ->
                add(
                    buildJsonObject {
                        put("startLine", start)
                        put("endLine", end)
                    },
                )
            }
        }
    }

    // Around the first position: the word, the line without its indentation, the declarations around it (their
    // whole text, innermost first), then the file, each one the parent of the previous one.
    fun selectionRanges(params: JsonElement?): JsonElement {
        val fields = params as? JsonObject ?: return JsonNull
        val uri = fields["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull ?: return JsonNull
        val position = (fields["positions"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return JsonNull
        val line = position["line"]?.jsonPrimitive?.int ?: return JsonNull
        val column = position["character"]?.jsonPrimitive?.int ?: return JsonNull
        val lines = code.files[uri].orEmpty()
        val text = lines.getOrNull(line) ?: return JsonNull
        val ranges = ArrayList<List<Int>>()
        code.wordAt(uri, line, column)?.let { (word, start) -> ranges += listOf(line, start, line, start + word.length) }
        val indent = text.length - text.trimStart().length
        ranges += listOf(line, indent, line, text.trimEnd().length)
        code.declarations
            .filter { it.uri == uri && line in it.line..it.endLine }
            .sortedBy { it.endLine - it.line }
            .forEach { ranges += listOf(it.line, it.start, it.endLine, lines.getOrNull(it.endLine)?.length ?: 0) }
        ranges += listOf(0, 0, lines.lastIndex.coerceAtLeast(0), lines.lastOrNull()?.length ?: 0)
        var chain: JsonObject? = null
        for (range in ranges.distinct().reversed()) {
            val parent = chain
            chain =
                buildJsonObject {
                    put("range", range(range[0], range[1], range[2], range[3]))
                    parent?.let { put("parent", it) }
                }
        }
        return buildJsonArray { chain?.let { add(it) } }
    }

    // The declarations of every file whose name contains the query, ignoring case, as SymbolInformations.
    fun workspaceSymbols(params: JsonElement?): JsonElement {
        val query = (params as? JsonObject)?.get("query")?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        return buildJsonArray {
            code.declarations.filter { query in it.name.lowercase() }.forEach { declaration ->
                addJsonObject {
                    put("name", declaration.name)
                    put("kind", declaration.kind)
                    declaration.parent?.let { put("containerName", it) }
                    putJsonObject("location") {
                        put("uri", declaration.uri)
                        put("range", range(declaration.line, declaration.column, declaration.line, declaration.column + declaration.name.length))
                    }
                }
            }
        }
    }

    private fun symbolOf(declaration: FakeDeclaration, lines: List<String>) =
        buildJsonObject {
            put("name", declaration.name)
            put("kind", declaration.kind)
            put("range", range(declaration.line, declaration.start, declaration.endLine, lines.getOrNull(declaration.endLine)?.length ?: 0))
            put("selectionRange", range(declaration.line, declaration.column, declaration.line, declaration.column + declaration.name.length))
        }

    fun signatureHelp(params: JsonElement?): JsonElement {
        val uri = uriOf(params) ?: return JsonNull
        val (line, column) = positionOf(params) ?: return JsonNull
        val text = code.files[uri]?.getOrNull(line)?.take(column) ?: return JsonNull
        var depth = 0
        var commas = 0
        var open = -1
        for (index in text.indices.reversed()) {
            when (text[index]) {
                ')' -> depth++
                '(' -> if (depth == 0) {
                    open = index
                    break
                } else {
                    depth--
                }
                ',' -> if (depth == 0) commas++
            }
        }
        if (open < 0) return JsonNull
        val name = Regex("""(\w+)\s*$""").find(text.substring(0, open))?.groupValues?.get(1) ?: return JsonNull
        val declared = code.declarationsNamed(name).firstOrNull()
        val signature = declared?.signature ?: FakePhpCode.BUILT_INS[name]?.first ?: return JsonNull
        val label = signature.removePrefix("public ").removePrefix("function ").trim()
        val parameters = label.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return buildJsonObject {
            putJsonArray("signatures") {
                addJsonObject {
                    put("label", label)
                    declared?.documentation?.lineSequence()?.firstOrNull()?.let { doc ->
                        putJsonObject("documentation") {
                            put("kind", "markdown")
                            put("value", doc)
                        }
                    }
                    putJsonArray("parameters") {
                        parameters.forEach { parameter ->
                            addJsonObject {
                                put("label", parameter)
                                putJsonObject("documentation") {
                                    put("kind", "markdown")
                                    put("value", parameter)
                                }
                            }
                        }
                    }
                }
            }
            put("activeSignature", 0)
            put("activeParameter", commas)
        }
    }

    fun prepareRename(params: JsonElement?): JsonElement {
        val (uri, word, start, line) = wordOf(params) ?: return JsonNull
        val known = code.declarationsNamed(word).isNotEmpty() || (word.startsWith("$") && code.assignmentOf(word, uri) != null)
        return if (known) range(line, start, line, start + word.length) else JsonNull
    }

    fun rename(params: JsonElement?): JsonElement {
        val (uri, word, _, _) = wordOf(params) ?: return JsonNull
        val newName = params?.jsonObject?.get("newName")?.jsonPrimitive?.contentOrNull ?: return JsonNull
        return renameEdit(word, uri, newName)
    }

    /** The WorkspaceEdit renaming the whole-word occurrences of [word] (seen from [uri]) to [newName]. */
    fun renameEdit(word: String, uri: String, newName: String): JsonObject {
        val byUri = code.occurrences(word, uri).groupBy { it.uri }
        return buildJsonObject {
            putJsonArray("documentChanges") {
                byUri.forEach { (fileUri, occurrences) ->
                    addJsonObject {
                        putJsonObject("textDocument") {
                            put("uri", fileUri)
                            put("version", JsonNull)
                        }
                        putJsonArray("edits") {
                            occurrences.forEach { add(edit(it.line, it.column, it.line, it.column + it.length, newName)) }
                        }
                    }
                }
            }
        }
    }

    /** Tabs at the start of a line become 4 spaces, trailing spaces go: one edit per changed line. */
    fun format(params: JsonElement?, range: Boolean): JsonElement {
        val uri = uriOf(params) ?: return JsonNull
        val lines = code.files[uri].orEmpty()
        val rangeObject = if (range) params?.jsonObject?.get("range")?.jsonObject else null
        val first = rangeObject?.get("start")?.jsonObject?.get("line")?.jsonPrimitive?.int ?: 0
        val last = rangeObject?.get("end")?.jsonObject?.get("line")?.jsonPrimitive?.int ?: lines.lastIndex
        return buildJsonArray {
            for (index in first..last.coerceAtMost(lines.lastIndex)) {
                val text = lines[index]
                val indent = text.takeWhile { it == ' ' || it == '\t' }
                val formatted = indent.replace("\t", "    ") + text.substring(indent.length).trimEnd()
                if (formatted != text) add(edit(index, 0, index, text.length, formatted))
            }
        }
    }

    /** "Create function x" for an unknown call (an edit), "Add docblock" on a declaration without one (a command). */
    fun codeActions(params: JsonElement?): JsonElement {
        val uri = uriOf(params) ?: return JsonArray(emptyList())
        val range = params?.jsonObject?.get("range")?.jsonObject
        val line = range?.get("start")?.jsonObject?.get("line")?.jsonPrimitive?.int ?: return JsonArray(emptyList())
        val lines = code.files[uri].orEmpty()
        val unknown = code.unknownCalls(uri).filter { it.line == line }
        val declaration = code.declarations.firstOrNull { it.uri == uri && it.line == line && it.documentation == null }
        return buildJsonArray {
            unknown.forEach { call ->
                val name = lines[call.line].substring(call.column, call.column + call.length)
                addJsonObject {
                    put("title", "Create function $name")
                    put("kind", "quickfix.create_function")
                    put("isPreferred", true)
                    putJsonObject("edit") {
                        putJsonObject("changes") {
                            putJsonArray(uri) {
                                add(edit(lines.size, 0, lines.size, 0, "\nfunction $name()\n{\n}\n"))
                            }
                        }
                    }
                }
            }
            if (declaration != null) {
                addJsonObject {
                    put("title", "Add docblock")
                    put("kind", "refactor.add_docblock")
                    putJsonObject("command") {
                        put("title", "Add docblock")
                        put("command", "add_docblock")
                        putJsonArray("arguments") {
                            add(uri)
                            add(line)
                        }
                    }
                }
            }
        }
    }

    /** The edit `add_docblock` asks the client to apply. */
    fun docblockEdit(arguments: JsonArray?): JsonObject? {
        val uri = (arguments?.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return null
        val line = (arguments.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val text = code.files[uri]?.getOrNull(line) ?: return null
        val indent = text.takeWhile { it.isWhitespace() }
        return buildJsonObject {
            put("label", "Add docblock")
            putJsonObject("edit") {
                putJsonObject("changes") {
                    putJsonArray(uri) { add(edit(line, 0, line, 0, "$indent/**\n$indent * TODO\n$indent */\n")) }
                }
            }
        }
    }

    /** One error per call of an unknown function, like phpactor's `worse.unresolved_name`. */
    fun diagnostics(uri: String): JsonArray {
        val lines = code.files[uri].orEmpty()
        return buildJsonArray {
            code.unknownCalls(uri).forEach { call ->
                val name = lines[call.line].substring(call.column, call.column + call.length)
                addJsonObject {
                    put("range", range(call.line, call.column, call.line, call.column + call.length))
                    put("severity", 1)
                    put("code", "worse.unresolved_name")
                    put("source", "fake")
                    put("message", "Function \"$name\" not found")
                }
            }
            lines.forEachIndexed { index, text ->
                val column = text.indexOf("@deprecated-call")
                if (column >= 0) {
                    addJsonObject {
                        put("range", range(index, column, index, column + "@deprecated-call".length))
                        put("severity", 2)
                        put("message", "Call to a deprecated function")
                        putJsonArray("tags") { add(2) }
                    }
                }
            }
        }
    }

    private data class Word(val uri: String, val word: String, val start: Int, val line: Int)

    private fun wordOf(params: JsonElement?): Word? {
        val uri = uriOf(params) ?: return null
        val (line, column) = positionOf(params) ?: return null
        val (word, start) = code.wordAt(uri, line, column) ?: return null
        return Word(uri, word, start, line)
    }

    private fun uriOf(params: JsonElement?): String? {
        val textDocument = (params as? JsonObject)?.get("textDocument") as? JsonObject
        return (textDocument?.get("uri") as? JsonPrimitive)?.contentOrNull
    }

    private fun positionOf(params: JsonElement?): Pair<Int, Int>? {
        val position = (params as? JsonObject)?.get("position") as? JsonObject ?: return null
        return position.getValue("line").jsonPrimitive.int to position.getValue("character").jsonPrimitive.int
    }

    private fun location(uri: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        buildJsonObject {
            put("uri", uri)
            put("range", range(startLine, startColumn, endLine, endColumn))
        }

    private fun edit(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, text: String) =
        buildJsonObject {
            put("range", range(startLine, startColumn, endLine, endColumn))
            put("newText", text)
        }

    private companion object {
        const val READ = 2
        const val WRITE = 3

        fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
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
    }
}
