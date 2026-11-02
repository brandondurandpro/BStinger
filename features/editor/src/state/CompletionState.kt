package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionResult

// Rows of the popup a Page Up or Page Down moves by.
private const val PAGE_ROWS = 8

// How long an item stays selected before its documentation is asked: the arrows run through items without asking.
internal const val DOCUMENTATION_DELAY_MS = 200L

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
    /** Names the answer to resolve its items; empty when they cannot be. */
    internal val token: String = "",
) {
    /** The documentation of the selected item (Markdown), null while unknown or when it has none. */
    var documentation: String? by mutableStateOf(null)
        internal set

    // Documentation already resolved, by resolve index: moving back to an item does not ask again. An item without
    // (or whose request failed, the link being down) is asked again when selected again.
    internal val resolved = HashMap<Int, String>()

    /** The selected item. */
    val selectedItem: CompletionItem? get() = items.getOrNull(selected)

    /** The items matching the text typed since [replaceFrom], best first. */
    var items: List<CompletionItem> by mutableStateOf(emptyList())
        private set

    /** Index of the selected item in [items]. */
    var selected by mutableIntStateOf(0)
        internal set

    // Chosen with the arrows: a newer answer keeps it rather than its best item.
    internal var chosenByUser = false
        private set

    /** The text between [replaceFrom] and the caret, which the items match. */
    var typed by mutableStateOf("")
        private set

    // The label of the item chosen with the arrows, if any.
    internal fun chosenLabel(): String? = items.getOrNull(selected)?.label?.takeIf { chosenByUser }

    internal fun choose(index: Int) {
        selected = index
        chosenByUser = true
    }

    internal fun refilter() {
        val caret = document.caret
        val current = document.lines.getOrNull(line).orEmpty()
        val typedStart = replaceFrom.coerceAtMost(caret.column)
        val typedEnd = caret.column.coerceAtMost(current.length)
        val now = current.substring(typedStart, typedEnd)
        val unchanged = now == typed && items.isNotEmpty()
        if (unchanged) return
        // The item chosen with the arrows stays chosen; otherwise the best match is.
        val previous = items.getOrNull(selected)?.takeIf { chosenByUser }
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
    // The documentation of an item of a completion answer (token, resolve index).
    private val resolve: suspend (token: String, index: Int) -> String? = { _, _ -> null },
    // The server's proposals at the caret of a document.
    private val source: suspend (path: String, document: Document) -> CompletionResult,
) {
    /** The popup shown, null when none. */
    var popup: CompletionPopup? by mutableStateOf(null)
        private set

    private var job: Job? = null
    private val describer = Describer(scope, resolve) { popup }

    /** Whether the documentation of the selected item is being asked (waiting, or in flight). */
    internal val describing: Boolean get() = describer.active

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
                describer.describe()
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
            describer.describe()
        }
    }

    /** Moves the selection by [delta] rows, wrapping around. */
    fun move(delta: Int) {
        val shown = popup ?: return
        if (shown.items.isNotEmpty()) shown.choose((shown.selected + delta).mod(shown.items.size))
        describer.describe()
    }

    /** Moves the selection by a page, without wrapping. */
    fun page(direction: Int) {
        val shown = popup ?: return
        val last = (shown.items.size - 1).coerceAtLeast(0)
        shown.choose((shown.selected + direction * PAGE_ROWS).coerceIn(0, last))
        describer.describe()
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
        describer.cancel()
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
        val chosen = popup?.takeIf { it.document === document }?.chosenLabel()
        val next =
            CompletionPopup(
                document,
                caret.line,
                result.replaceFrom,
                result.items,
                result.token,
            ).apply { refilter() }
        // A late answer keeps the item chosen with the arrows meanwhile.
        val kept = next.items.indexOfFirst { it.label == chosen }
        if (kept >= 0) next.choose(kept)
        popup = next
        describer.describe()
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}

/**
 * Shows the documentation of the selected item of the [current] popup: sent with it, already resolved, or asked to
 * the server once it stays selected for [DOCUMENTATION_DELAY_MS].
 */
private class Describer(
    private val scope: CoroutineScope,
    private val resolve: suspend (token: String, index: Int) -> String?,
    private val current: () -> CompletionPopup?,
) {
    private var job: Job? = null

    val active: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun describe() {
        cancel()
        val shown = current() ?: return
        val item = shown.selectedItem
        val index = item?.resolveIndex ?: -1
        val known = item?.documentation ?: shown.resolved[index]
        val resolvable = shown.token.isNotEmpty() && index >= 0
        shown.documentation = known
        if (known != null || !resolvable) return
        job =
            scope.launch {
                delay(DOCUMENTATION_DELAY_MS)
                val documentation = resolve(shown.token, index)
                documentation?.let { shown.resolved[index] = it }
                // Another item may be selected meanwhile: its documentation is not this one.
                val stillSelected = current() === shown && shown.selectedItem?.resolveIndex == index
                if (stillSelected) shown.documentation = documentation
            }
    }
}
