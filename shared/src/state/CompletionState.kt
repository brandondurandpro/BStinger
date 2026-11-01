package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionResult

// Rows of the popup a Page Up or Page Down moves by.
private const val PAGE_ROWS = 8

/** What the completion popup shows: the server's items for a word, filtered by what was typed since. */
@Stable
class CompletionPopup internal constructor(
    /** The document it completes. */
    val document: Document,
    /** Line of the word being completed. */
    val line: Int,
    /** Column where the replaced text starts. */
    val replaceFrom: Int,
    /** Every item of the last answer. */
    private val all: List<CompletionItem>,
) {
    /** The items matching the text typed since [replaceFrom], best first. */
    var items: List<CompletionItem> by mutableStateOf(emptyList())
        private set

    /** Index of the selected item in [items]. */
    var selected by mutableIntStateOf(0)
        internal set

    /** The text between [replaceFrom] and the caret, which the items match. */
    var typed by mutableStateOf("")
        private set

    internal fun refilter() {
        val caret = document.caret
        val current = document.lines.getOrNull(line).orEmpty()
        val typedStart = replaceFrom.coerceAtMost(caret.column)
        val typedEnd = caret.column.coerceAtMost(current.length)
        val now = current.substring(typedStart, typedEnd)
        val unchanged = now == typed && items.isNotEmpty()
        if (unchanged) return
        val previous = items.getOrNull(selected)
        typed = now
        // Best match first; among equal matches, the server's order.
        val matching =
            all.mapIndexedNotNull { index, item ->
                val rank = matchRank(now, item.insertText)
                rank?.let { Triple(it, index, item) }
            }
        val sorted = matching.sortedWith(compareBy({ it.first }, { it.second }))
        items = sorted.map { it.third }
        selected = items.indexOf(previous).coerceAtLeast(0)
    }
}

/** How well [typed] matches [candidate], lower is better; null when it does not (first letter, then in order). */
internal fun matchRank(typed: String, candidate: String): Int? {
    var matched = 0
    for (c in candidate) {
        val expected = typed.getOrNull(matched)
        if (expected != null && c.equals(expected, ignoreCase = true)) matched++
    }
    val isExactPrefix = typed.isEmpty() || candidate.startsWith(typed)
    val isPrefixIgnoringCase = candidate.startsWith(typed, ignoreCase = true)
    val firstTyped = typed.firstOrNull()
    val firstOfCandidate = candidate.firstOrNull()
    val sameFirstLetter =
        firstTyped != null && firstOfCandidate != null && firstTyped.equals(firstOfCandidate, ignoreCase = true)
    val isSpreadMatch = sameFirstLetter && matched == typed.length
    return when {
        isExactPrefix -> 0
        isPrefixIgnoringCase -> 1
        isSpreadMatch -> 2
        else -> null
    }
}

/**
 * Code completion in the editor (see #97): the popup opens by itself while a word is typed in a file that has a
 * language server (or with Ctrl+Space), shows what the server proposes, and inserts the chosen item. Each keystroke
 * filters the items at once and asks the server again; an answer for an older text is dropped, so a late answer
 * never replaces a newer one.
 */
@Stable
class CompletionState(
    private val scope: CoroutineScope,
    // The server's proposals at the caret of a document.
    private val source: suspend (path: String, document: Document) -> CompletionResult,
) {
    /** The popup shown, null when none. */
    var popup: CompletionPopup? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Ctrl+Space: asks for the proposals at the caret. */
    fun explicit(path: String, document: Document) = request(path, document, keepOpen = true)

    /** A character was typed in [document]: opens the popup inside a word or after `->`, `::`, `\`, `$`. */
    fun typed(path: String, document: Document, c: Char) {
        val shown = popup
        val line = document.lines[document.caret.line]
        val before = line.substring(0, document.caret.column)
        val startsWord = isWordChar(c) && !c.isDigit()
        val afterAccess = before.endsWith("->") || before.endsWith("::")
        val startsName = c == '\\' || c == '$'
        val trigger = startsWord || afterAccess || startsName
        when {
            shown != null && shown.document === document && isWordChar(c) -> {
                shown.refilter()
                request(path, document, keepOpen = true)
            }

            trigger -> {
                request(path, document, keepOpen = false)
            }

            else -> {
                dismiss()
            }
        }
    }

    /** Text was deleted or pasted in [document]: an open popup asks again for the word as it is now. */
    fun edited(path: String, document: Document) {
        if (popup?.document === document) request(path, document, keepOpen = true)
    }

    /** The caret or the text moved: the popup follows the word, or closes when the caret left it. */
    fun caretMoved(document: Document) {
        val shown = popup ?: return
        val caret = document.caret
        val sameLine = shown.document === document && caret.line == shown.line
        if (!sameLine || caret.column < shown.replaceFrom) {
            dismiss()
        } else {
            shown.refilter()
        }
    }

    /** Moves the selection by [delta] rows, wrapping around. */
    fun move(delta: Int) {
        val shown = popup ?: return
        if (shown.items.isNotEmpty()) shown.selected = (shown.selected + delta).mod(shown.items.size)
    }

    /** Moves the selection by a page, without wrapping. */
    fun page(direction: Int) {
        val shown = popup ?: return
        shown.selected = (shown.selected + direction * PAGE_ROWS).coerceIn(0, (shown.items.size - 1).coerceAtLeast(0))
    }

    /**
     * Inserts the selected item, or [index]: it replaces the typed text, and with [replaceWord] (Tab) the rest of
     * the word after the caret too.
     */
    fun accept(replaceWord: Boolean = false, index: Int? = null) {
        val shown = popup ?: return
        val chosenIndex = index ?: shown.selected
        val item = shown.items.getOrNull(chosenIndex) ?: return
        val document = shown.document
        val caret = document.caret
        val line = document.lines[caret.line]
        var end = caret.column
        if (replaceWord) while (end < line.length && isWordChar(line[end])) end++
        dismiss()
        document.replace(TextPos(caret.line, shown.replaceFrom), TextPos(caret.line, end), item.insertText)
    }

    /** Closes the popup and forgets the request in flight. */
    fun dismiss() {
        job?.cancel()
        job = null
        popup = null
    }

    private fun request(path: String, document: Document, keepOpen: Boolean) {
        job?.cancel()
        if (!keepOpen) popup = null
        val version = document.version
        val caret = document.caret
        job =
            scope.launch {
                val result = source(path, document)
                // Typed or moved meanwhile: a newer request is on its way, or the popup was closed.
                if (document.version == version && document.caret == caret) show(document, caret, result)
            }
    }

    private fun show(document: Document, caret: TextPos, result: CompletionResult) {
        val nothing = !result.ready || result.items.isEmpty()
        if (nothing || result.replaceFrom > caret.column) {
            popup = null
            return
        }
        popup = CompletionPopup(document, caret.line, result.replaceFrom, result.items).apply { refilter() }
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
