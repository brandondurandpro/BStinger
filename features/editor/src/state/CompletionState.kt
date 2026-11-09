package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import org.example.kotlineditor.telemetry.sizeOf

// Files where `.` is the concatenation operator, not a member access.
private val PHP_EXTENSIONS = setOf("php", "phtml", "inc")

// Rows of the popup a Page Up or Page Down moves by.
private const val PAGE_ROWS = 8

// Rows of the popup whose type is asked at once: the ones the list shows.
private const val DETAILED_ROWS = 12

// How long the list stays unchanged before the types of its rows are asked.
private const val DETAILS_DELAY_MS = 100L
private const val NOT_READY_RETRIES = 8
private const val NOT_READY_RETRY_MS = 500L

// How long an item stays selected before its documentation is asked: the arrows run through items without asking.
internal const val DOCUMENTATION_DELAY_MS = 200L

// A server that is still loading the project answers "not ready": ask again, as long as nothing changed.
private suspend fun askUntilReady(ask: suspend () -> CompletionResult): CompletionResult {
    var result = ask()
    var attempts = 0
    while (!result.ready && attempts < NOT_READY_RETRIES) {
        attempts++
        delay(NOT_READY_RETRY_MS)
        result = ask()
    }
    return result
}

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

    /**
     * The type or signature of the items whose server gives it only once they are resolved, by resolve index: the
     * rows show it at their end, as they do the `detail` the list carries.
     */
    val details = mutableStateMapOf<Int, String>()

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
        val afterAccess = before.endsWith("->") || before.endsWith("::") || isMemberDot(path, before)
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
                // From the keystroke to the frame that shows the items; a newer keystroke cancels it.
                ClientTelemetry.measureToFrame(Operation.EditorCompletion, attributesOf = ::itemsOf) {
                    val result = askUntilReady { source(path, document) }
                    // Typed or moved meanwhile: a newer request is on its way, or the popup was closed.
                    if (document.version == version && document.caret == caret) show(document, caret, result)
                    result
                }
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
    private var detailsJob: Job? = null

    val active: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
        detailsJob?.cancel()
        detailsJob = null
    }

    // The types of the first rows of the list that the server gives only on resolve: asked together, once the list
    // stops changing; the answers are kept, so the documentation of an item is not asked a second time.
    private fun detailRows() {
        detailsJob?.cancel()
        val shown = current() ?: return
        val wanted =
            shown.items
                .take(DETAILED_ROWS)
                .filter { it.detail == null && it.resolveIndex >= 0 && it.resolveIndex !in shown.details }
        if (shown.token.isEmpty() || wanted.isEmpty()) return
        detailsJob =
            scope.launch {
                delay(DETAILS_DELAY_MS)
                wanted
                    .map { item -> async { item.resolveIndex to resolve(shown.token, item.resolveIndex) } }
                    .awaitAll()
                    .forEach { (index, markdown) ->
                        markdown?.let { shown.resolved.putIfAbsent(index, it) }
                        signatureOf(markdown)?.let { shown.details[index] = finalTypeOf(it) }
                    }
            }
    }

    fun describe() {
        cancel()
        detailRows()
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

private fun itemsOf(result: CompletionResult): Attributes {
    val found =
        when {
            !result.ready -> "partial"
            result.items.isEmpty() -> "empty"
            else -> "found"
        }
    return sizeOf(result.items.size) + Attributes.of(Attribute.Result to found)
}

// The type or signature a resolved item starts with: the first fenced block of its Markdown (see the resolve of
// the language servers), null when it starts with something else.
internal fun signatureOf(markdown: String?): String? {
    val fence = "```"
    val body = markdown?.takeIf { it.startsWith("$fence\n") }?.substring(fence.length + 1)
    val end = body?.indexOf("\n$fence") ?: -1
    val signature = if (end < 0) null else body?.substring(0, end)
    return signature?.lineSequence()?.joinToString(" ") { it.trim() }?.takeIf { it.isNotBlank() }
}

/**
 * What a row shows of a signature such as `(method) Date.getDate(): number`: its final type, `number`. It is what
 * follows the last `: ` outside parentheses, brackets and angle brackets; a signature without one (`class Date`) is
 * left as it is.
 */
internal fun finalTypeOf(signature: String): String {
    var depth = 0
    var colon = -1
    signature.forEachIndexed { index, c ->
        when {
            c in "(<[{" -> depth++
            c in ")>]}" && !(c == '>' && signature.getOrNull(index - 1) == '=') -> depth--
            c == ':' && depth == 0 && signature.getOrNull(index + 1) == ' ' -> colon = index
        }
    }
    val type = if (colon < 0) "" else signature.substring(colon + 2).trim()
    return type.ifEmpty { signature }
}

// A `.` that opens a member access (`oDate.`, `list[0].`, `f().`), not a PHP concatenation nor a number (`1.`).
private fun isMemberDot(path: String, before: String): Boolean {
    val extension = path.substringAfterLast('.').lowercase()
    val operand = before.dropLast(1)
    val last = operand.lastOrNull() ?: return false
    val word = operand.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
    val endsOperand = last in ")]" || word.any { it.isLetter() || it == '_' || it == '$' }
    return before.endsWith(".") && extension !in PHP_EXTENSIONS && endsOperand
}
