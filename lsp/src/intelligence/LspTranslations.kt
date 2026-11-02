@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.CodeActionInfo
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.HighlightKind
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SignatureInfo
import org.example.kotlineditor.model.SignatureParameter
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import java.net.URI
import java.nio.file.Paths

private const val RANGE = "range"
private const val KIND = "kind"
private const val LINE = "line"
private const val CHARACTER = "character"
private const val URI = "uri"

// Readers of what language servers answer, written for what servers really send: a Location or an array of them,
// LocationLinks, MarkedStrings or MarkupContent, TextEdits or documentChanges. Anything malformed is skipped.

private const val SEVERITY_ERROR = 1
private const val SEVERITY_WARNING = 2
private const val SEVERITY_INFORMATION = 3
private const val TAG_UNNECESSARY = 1
private const val TAG_DEPRECATED = 2
private const val HIGHLIGHT_READ = 2
private const val HIGHLIGHT_WRITE = 3

private const val RANGE_NUMBERS = 4
private val MARKDOWN_RULE = listOf("", "", "---", "", "").joinToString("\n")
private val BY_RANGE_START = compareBy<CountedSymbol>({ it.range.startLine }, { it.range.startColumn })

// The declarations whose usages are counted: classes, methods, properties, functions, constants...
private val COUNTED_SYMBOL_KINDS = setOf(5, 6, 7, 8, 10, 11, 12, 14, 22, 23)

internal fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

/** Path of a `file:` URI, null for any other scheme (`phar:`, `untitled:`...). */
fun pathOfUri(uri: String): String? {
    if (!uri.startsWith("file:")) return null
    return runCatching { Paths.get(URI(uri)).toString() }.getOrNull()
}

/** `{start: {line, character}, end: {...}}`. */
fun lspRange(range: TextRange): JsonObject =
    buildJsonObject {
        putJsonObject("start") {
            put(LINE, range.startLine)
            put(CHARACTER, range.startColumn)
        }
        putJsonObject("end") {
            put(LINE, range.endLine)
            put(CHARACTER, range.endColumn)
        }
    }

/** Reads an LSP range. */
fun JsonElement?.textRange(): TextRange? {
    val range = this as? JsonObject
    val start = range?.obj("start")
    val end = range?.obj("end")
    val numbers = listOfNotNull(start?.int(LINE), start?.int(CHARACTER), end?.int(LINE), end?.int(CHARACTER))
    return numbers.takeIf { it.size == RANGE_NUMBERS }?.let(::rangeOf)
}

// Start line and column, end line and column.
private fun rangeOf(numbers: List<Int>): TextRange {
    val (startLine, startColumn) = numbers
    val (endLine, endColumn) = numbers.drop(2)
    return TextRange(startLine, startColumn, endLine, endColumn)
}

/**
 * The places of a definition, type definition, implementation or references answer: null, a Location, an array of
 * Locations or of LocationLinks. Places outside of `file:` URIs (a phar's stubs) are left out.
 */
fun locationsOf(answer: JsonElement?): List<SourceLocation> {
    val elements =
        when (answer) {
            is JsonArray -> answer.toList()
            is JsonObject -> listOf(answer)
            else -> emptyList()
        }
    return elements.mapNotNull { locationOf(it as? JsonObject) }.distinct()
}

private fun locationOf(element: JsonObject?): SourceLocation? {
    // A LocationLink goes to its target, preferably to the name (selection range).
    val uri = element?.string(URI) ?: element?.string("targetUri")
    val target = element?.get("targetSelectionRange").textRange() ?: element?.get("targetRange").textRange()
    val range = element?.get(RANGE).textRange() ?: target
    val path = uri?.let(::pathOfUri)
    return if (path != null && range != null) SourceLocation(path, range) else null
}

/**
 * The Markdown of a hover answer: MarkupContent (markdown or plain text), a MarkedString (a string or a
 * `{language, value}` code block) or an array of MarkedStrings; null when it says nothing.
 */
fun hoverMarkdown(answer: JsonElement?): String? {
    val hover = answer as? JsonObject ?: return null
    val markdown = contentsMarkdown(hover["contents"])
    return markdown?.trim()?.takeIf { it.isNotEmpty() }
}

/** Markdown of documentation: a string, MarkupContent or MarkedString(s). */
fun contentsMarkdown(contents: JsonElement?): String? =
    when (contents) {
        is JsonPrimitive -> {
            contents.contentOrNull
        }

        is JsonArray -> {
            val parts = contents.mapNotNull(::contentsMarkdown)
            parts.filter { it.isNotBlank() }.joinToString(MARKDOWN_RULE)
        }

        is JsonObject -> {
            objectMarkdown(contents)
        }

        else -> {
            null
        }
    }

private fun objectMarkdown(contents: JsonObject): String? {
    val value = contents.string("value") ?: return null
    val language = contents.string("language")
    val kind = contents.string(KIND)
    return when {
        language != null -> "```$language\n$value\n```"
        kind == "plaintext" -> escapeMarkdown(value)
        else -> value
    }
}

// Plain text shown as Markdown: the characters Markdown would interpret are escaped, line breaks kept.
private fun escapeMarkdown(text: String): String {
    val escaped = text.replace(Regex("""([\\`*_\[\]#<>])"""), """\\$1""")
    return escaped.replace("\n", "  \n")
}

/** The occurrences of a documentHighlight answer. */
fun highlightsOf(answer: JsonElement?): List<SymbolHighlight> {
    val items = answer as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val item = element as? JsonObject
        val range = item?.get(RANGE).textRange()
        val kind =
            when (item?.int(KIND)) {
                HIGHLIGHT_READ -> HighlightKind.Read
                HIGHLIGHT_WRITE -> HighlightKind.Write
                else -> HighlightKind.Text
            }
        range?.let { SymbolHighlight(it, kind) }
    }
}

/** A signatureHelp answer; parameters given as labels are found in the signature's label. */
fun signatureHelpOf(answer: JsonElement?): SignatureHelpResult {
    val help = answer as? JsonObject
    val signatureElements = help?.array("signatures").orEmpty()
    val signatures = signatureElements.mapNotNull { signatureOf(it as? JsonObject) }
    val activeSignature = (help?.int("activeSignature") ?: 0).coerceIn(0, (signatures.size - 1).coerceAtLeast(0))
    val signatureElement = signatureElements.getOrNull(activeSignature) as? JsonObject
    // The signature's own active parameter wins over the answer's (LSP 3.16).
    val activeParameter = signatureElement?.int("activeParameter") ?: help?.int("activeParameter") ?: 0
    return SignatureHelpResult(FeatureStatus.Ready, signatures, activeSignature, activeParameter)
}

private fun signatureOf(signature: JsonObject?): SignatureInfo? {
    val label = signature?.string("label") ?: return null
    var searchFrom = 0
    val parameters =
        signature.array("parameters").orEmpty().mapNotNull { element ->
            val parameter = element as? JsonObject
            val bounds = parameterBounds(parameter?.get("label"), label, searchFrom)
            bounds?.let {
                searchFrom = it.second
                SignatureParameter(
                    it.first,
                    it.second,
                    contentsMarkdown(parameter?.get("documentation"))?.takeIf(String::isNotBlank),
                )
            }
        }
    val documentation = contentsMarkdown(signature["documentation"])?.takeIf { it.isNotBlank() }
    return SignatureInfo(label, documentation, parameters)
}

// A parameter's label is a string found in the signature's label, or `[start, end]` offsets in it.
private fun parameterBounds(label: JsonElement?, signature: String, from: Int): Pair<Int, Int>? =
    when (label) {
        is JsonPrimitive -> {
            val text = label.contentOrNull.orEmpty()
            val start = signature.indexOf(text, from).takeIf { it >= 0 && text.isNotEmpty() }
            start?.let { it to it + text.length }
        }

        is JsonArray -> {
            val start = (label.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val end = (label.getOrNull(1) as? JsonPrimitive)?.intOrNull
            if (start != null && end != null) {
                start.coerceIn(
                    0,
                    signature.length,
                ) to end.coerceIn(start, signature.length)
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

/** A declaration of a document whose usages are counted. */
data class CountedSymbol(
    /** Its name. */
    val name: String,
    /** The range of its name. */
    val range: TextRange,
)

/**
 * The declarations of a documentSymbol answer (DocumentSymbols with children, or SymbolInformations), in document
 * order, with the range of their name.
 */
fun countedSymbolsOf(answer: JsonElement?): List<CountedSymbol> {
    val found = ArrayList<CountedSymbol>()

    fun visit(element: JsonElement) {
        val symbol = element as? JsonObject ?: return
        val name = symbol.string("name")
        val kind = symbol.int(KIND)
        val location = symbol.obj("location")
        val range = symbol["selectionRange"].textRange() ?: location?.get(RANGE).textRange()
        val counted = kind in COUNTED_SYMBOL_KINDS
        if (counted && name != null) range?.let { found += CountedSymbol(name, it) }
        symbol.array("children")?.forEach(::visit)
    }
    (answer as? JsonArray)?.forEach(::visit)
    return found.sortedWith(BY_RANGE_START)
}

/** The diagnostics of a publishDiagnostics notification. */
fun diagnosticsOf(params: JsonObject): List<Diagnostic> {
    val diagnostics = params.array("diagnostics").orEmpty().mapNotNull { diagnosticOf(it as? JsonObject) }
    return diagnostics.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }))
}

private fun diagnosticOf(diagnostic: JsonObject?): Diagnostic? {
    val range = diagnostic?.get(RANGE).textRange()
    val message = diagnostic?.string("message")?.trim()?.takeIf { it.isNotEmpty() }
    val tags = diagnostic?.array("tags").orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    val code = diagnostic?.get("code") as? JsonPrimitive
    if (range == null || message == null) return null
    return Diagnostic(
        range = range,
        severity = severityOf(diagnostic.int("severity")),
        message = message,
        source = diagnostic.string("source"),
        code = code?.contentOrNull,
        unnecessary = TAG_UNNECESSARY in tags,
        deprecated = TAG_DEPRECATED in tags,
    )
}

private fun severityOf(severity: Int?): DiagnosticSeverity =
    when (severity) {
        SEVERITY_WARNING -> DiagnosticSeverity.Warning
        SEVERITY_INFORMATION -> DiagnosticSeverity.Information
        SEVERITY_ERROR, null -> DiagnosticSeverity.Error
        else -> DiagnosticSeverity.Hint
    }

// A TextEdit as the change of a document. An AnnotatedTextEdit is a TextEdit; a SnippetTextEdit has a snippet
// instead of newText.
private fun textChangeOf(edit: JsonElement): TextChange? {
    val fields = edit as? JsonObject
    val range = fields?.get(RANGE).textRange()
    val snippet = fields?.obj("snippet")?.string("value")
    val text = fields?.string("newText") ?: snippet
    if (range == null || text == null) return null
    return TextChange(range.startLine, range.startColumn, range.endLine, range.endColumn, text)
}

/** The edits of a list of TextEdits (a formatting answer). */
fun textChangesOf(answer: JsonElement?): List<TextChange> = (answer as? JsonArray).orEmpty().mapNotNull(::textChangeOf)

/**
 * The files a WorkspaceEdit changes: `changes` by URI, or `documentChanges` (TextDocumentEdits and CreateFile
 * operations). The edits of a file are merged when it appears several times; other operations (rename, delete of a
 * file) are not supported and make the whole edit refused, so nothing is half applied.
 */
fun workspaceEditOf(edit: JsonElement?): List<FileChange>? {
    val workspaceEdit = edit as? JsonObject ?: return emptyList()
    val byPath = LinkedHashMap<String, FileChange>()

    fun add(uri: String?, edits: List<TextChange>, create: Boolean = false): Boolean {
        val path = uri?.let(::pathOfUri) ?: return false
        val previous = byPath[path]
        byPath[path] = FileChange(path, previous?.edits.orEmpty() + edits, create || previous?.create == true)
        return true
    }
    val documentChanges = workspaceEdit.array("documentChanges")
    val supported =
        if (documentChanges != null) {
            documentChanges.all { change -> addDocumentChange(change as? JsonObject, ::add) }
        } else {
            workspaceEdit.obj("changes").orEmpty().all { (uri, edits) -> add(uri, textChangesOf(edits)) }
        }
    return if (supported) byPath.values.toList() else null
}

private fun addDocumentChange(change: JsonObject?, add: (String?, List<TextChange>, Boolean) -> Boolean): Boolean {
    if (change == null) return false
    val kind = change.string(KIND)
    return when (kind) {
        null -> add(change.obj("textDocument")?.string(URI), textChangesOf(change["edits"]), false)
        "create" -> add(change.string(URI), emptyList(), true)
        else -> false
    }
}

/** The actions of a codeAction answer: Commands and CodeActions, disabled ones left out. */
fun codeActionsOf(answer: JsonElement?): List<Pair<CodeActionInfo, JsonObject>> {
    val items = (answer as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val enabled = items.filter { it["disabled"] == null }
    val actions =
        enabled.filter { it.string("title") != null }.map { action ->
            val preferred = (action["isPreferred"] as? JsonPrimitive)?.booleanOrNull == true
            CodeActionInfo(action.string("title").orEmpty(), action.string(KIND), preferred) to action
        }
    // Preferred first, then quick fixes, then the rest, each in the server's order.
    return actions.sortedBy { (info, _) ->
        when {
            info.preferred -> 0
            info.kind?.startsWith("quickfix") == true -> 1
            else -> 2
        }
    }
}
