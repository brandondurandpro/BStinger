package org.example.kotlineditor.lsp.completion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionKind
import org.example.kotlineditor.model.CompletionResult

/** Most items sent to the client: the popup shows a dozen, and typing one more letter asks again. */
const val MAX_COMPLETION_ITEMS = 200

private const val INSERT_AS_SNIPPET = 2
private val SNIPPET_PLACEHOLDER = Regex("""\$\{\d+(?::([^}]*))?}|\$\d+""")

/** An item of the language server's answer, before filtering. */
private class Candidate(
    val item: CompletionItem,
    // What the typed text is compared to.
    val filterText: String,
    val sortText: String,
    // Column where the server's edit starts, when it gave one on the caret's line.
    val editStart: Int?,
)

// How well the typed text matches an item: lower is better, null when it does not.
private fun rank(typed: String, filter: String): Int? {
    val isExactPrefix = typed.isEmpty() || filter.startsWith(typed)
    val isPrefixIgnoringCase = filter.startsWith(typed, ignoreCase = true)
    // Like IntelliJ: the first letter must match, the others may be spread (`stu` finds `strtoupper`).
    val firstTyped = typed.firstOrNull()
    val firstOfFilter = filter.firstOrNull()
    val sameFirstLetter = firstTyped != null && firstOfFilter != null && firstTyped.equals(firstOfFilter, true)
    val isSpreadMatch = sameFirstLetter && isSubsequence(typed, filter)
    return when {
        isExactPrefix -> 0
        isPrefixIgnoringCase -> 1
        isSpreadMatch -> 2
        else -> null
    }
}

private fun isSubsequence(typed: String, filter: String): Boolean {
    var index = 0
    for (c in filter) {
        if (index < typed.length && c.equals(typed[index], ignoreCase = true)) index++
    }
    return index == typed.length
}

/**
 * Turns a `textDocument/completion` answer into what the popup shows (see #97): the items that match what was typed
 * before the caret, best first, at most [MAX_COMPLETION_ITEMS], each one inserted from the same column so that the
 * client has one range to replace.
 */
object CompletionAnswers {
    /** Start of the word being typed that ends at [column] of [line]: letters, digits, `_`, and PHP's `$`. */
    fun wordStart(line: String, column: Int): Int {
        var start = column.coerceIn(0, line.length)
        while (start > 0 && isWordChar(line[start - 1])) start--
        return start
    }

    /** The result for an [answer] (a `CompletionList`, an array of items, or null) at [column] of [line]. */
    fun translate(answer: JsonElement?, line: String, column: Int, max: Int = MAX_COMPLETION_ITEMS): CompletionResult {
        val caret = column.coerceIn(0, line.length)
        val typedWordStart = wordStart(line, caret)

        // The answer is a CompletionList, or directly the array of its items.
        val completionList = answer as? JsonObject
        val itemsOfList = completionList?.get("items") as? JsonArray
        val bareItems = answer as? JsonArray
        val items = itemsOfList ?: bareItems ?: JsonArray(emptyList())
        val isIncompleteElement = completionList?.get("isIncomplete") as? JsonPrimitive
        val serverIncomplete = isIncompleteElement?.booleanOrNull == true

        val itemObjects = items.filterIsInstance<JsonObject>()
        val candidates = itemObjects.mapNotNull { candidateOf(it, caret) }

        // One start for all: the leftmost one, items that start further right get the text in between.
        val editStarts = candidates.mapNotNull { it.editStart }
        val from = minOf(typedWordStart, editStarts.minOrNull() ?: typedWordStart)
        val ranked = candidates.mapNotNull { rankedFrom(it, line, from, typedWordStart, caret) }
        val sorted =
            ranked.sortedWith(
                compareBy<Triple<Int, Candidate, CompletionItem>> { it.first }
                    .thenBy { it.second.sortText }
                    .thenBy { it.second.item.label },
            )
        return CompletionResult(
            replaceFrom = from,
            items = sorted.take(max).map { it.third },
            incomplete = serverIncomplete || sorted.size > max,
            ready = true,
        )
    }

    // The candidate with its score, inserted from [from]; null when the typed text does not match it.
    private fun rankedFrom(
        candidate: Candidate,
        line: String,
        from: Int,
        typedWordStart: Int,
        caret: Int,
    ): Triple<Int, Candidate, CompletionItem>? {
        val start = candidate.editStart ?: typedWordStart
        val typed = line.substring(start, caret)
        val score = rank(typed, candidate.filterText)
        val between = line.substring(from, start)
        val shiftedItem = candidate.item.copy(insertText = between + candidate.item.insertText)
        return score?.let { Triple(it, candidate, shiftedItem) }
    }

    private fun candidateOf(item: JsonObject, caret: Int): Candidate? {
        val label = item.string("label") ?: return null
        val edit = item["textEdit"] as? JsonObject
        val insertedText = insertedTextOf(item, edit, label)

        val kindElement = item["kind"] as? JsonPrimitive
        val kindNumber = kindElement?.intOrNull
        val detail = item.string("detail")
        val visibleDetail = detail?.takeIf { it.isNotBlank() }
        val completionItem = CompletionItem(label, kindOf(kindNumber), visibleDetail, insertedText)

        val filterText = item.string("filterText") ?: label
        val sortText = item.string("sortText") ?: label
        return Candidate(completionItem, filterText, sortText, editStartOf(edit, caret))
    }

    // What accepting the item inserts: the edit's text, else insertText, else the label; snippets as plain text.
    private fun insertedTextOf(item: JsonObject, edit: JsonObject?, label: String): String {
        val editText = edit?.string("newText")
        val insertText = item.string("insertText")
        val text = editText ?: insertText ?: label
        val formatElement = item["insertTextFormat"] as? JsonPrimitive
        val isSnippet = formatElement?.intOrNull == INSERT_AS_SNIPPET
        return if (isSnippet) plain(text) else text
    }

    // Column where the edit starts, when it stays on the caret's line and starts before the caret.
    private fun editStartOf(edit: JsonObject?, caret: Int): Int? {
        // A TextEdit has a range; an InsertReplaceEdit has insert and replace ranges: the insert one is kept.
        val textEditRange = edit?.get("range") as? JsonObject
        val insertRange = edit?.get("insert") as? JsonObject
        val range = textEditRange ?: insertRange
        val start = range?.get("start") as? JsonObject
        val end = range?.get("end") as? JsonObject
        val startLine = start?.int("line")
        val endLine = end?.int("line")
        val isOnOneLine = startLine != null && startLine == endLine
        val startColumn = start?.int("character")
        return startColumn?.takeIf { isOnOneLine && it <= caret }
    }

    // A snippet without its tab stops: `strlen(${1:string})` becomes `strlen(string)`, `foo($0)` becomes `foo()`.
    private fun plain(snippet: String): String = SNIPPET_PLACEHOLDER.replace(snippet) { it.groupValues[1] }

    // The LSP CompletionItemKind numbers.
    @Suppress("MagicNumber", "CyclomaticComplexMethod")
    private fun kindOf(kind: Int?): CompletionKind =
        when (kind) {
            2, 4 -> CompletionKind.Method
            3 -> CompletionKind.Function
            5, 10 -> CompletionKind.Property
            6 -> CompletionKind.Variable
            7, 13, 22 -> CompletionKind.Class
            8 -> CompletionKind.Interface
            9 -> CompletionKind.Module
            14 -> CompletionKind.Keyword
            20, 21 -> CompletionKind.Constant
            else -> CompletionKind.Other
        }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
