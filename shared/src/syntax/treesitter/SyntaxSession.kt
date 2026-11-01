package org.example.kotlineditor.syntax.treesitter

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.Document

/**
 * The tree-sitter colors of some lines of a document, as computed by its last parse. Immutable: each parse publishes a
 * new one. A line edited (or moved by an edit) since keeps colors until the next parse ends, a few milliseconds
 * later: those of a line with the same text, else its previous ones cut to its length.
 */
@Immutable
class SyntaxHighlights internal constructor(
    private val byLine: Map<Int, Pair<String, LineSpans>>,
    /** Lines whose colors were computed, inclusive. */
    val window: IntRange,
) {
    private val byText: Map<String, LineSpans> by lazy {
        buildMap { byLine.values.forEach { (text, spans) -> putIfAbsent(text, spans) } }
    }

    /** The colors of line [index] whose text is now [text], `null` when they are not known (the lexer colors it). */
    fun spansFor(index: Int, text: String): LineSpans? {
        val entry = byLine[index]
        if (entry != null && entry.first == text) return entry.second
        return byText[text] ?: entry?.second?.clippedTo(text.length)
    }
}

/**
 * Highlighting of one document with the tree-sitter grammar [spec]: waits for the grammar (downloaded the first time),
 * then parses the document again after each change, off the UI thread, and colors the lines around the visible
 * ones ([request]). Until its first [highlights], and for files too large, the line lexer colors the document.
 */
@Stable
class SyntaxSession(val spec: GrammarSpec) {
    /** The latest colors, `null` until the grammar is there and the document parsed. */
    var highlights: SyntaxHighlights? by mutableStateOf(null)
        private set

    /**
     * Whether the document's syntax tree is in memory. It is freed while no editor shows the document, and parsed
     * again when one does; its last colors stay meanwhile.
     */
    var isParsed: Boolean by mutableStateOf(false)
        private set

    private val wanted = MutableStateFlow(0 until INITIAL_LINES)

    // Editors showing the document: its tree is only kept while there is one.
    private val shown = MutableStateFlow(0)

    /** An editor shows the document, until [hide]. */
    fun show() = shown.update { it + 1 }

    /** An editor no longer shows the document. */
    fun hide() = shown.update { it - 1 }

    /** Asks for the colors of lines [first] to [last] (inclusive), drawn now; cheap when they are already known. */
    fun request(first: Int, last: Int) {
        val window = highlights?.window
        if (window != null && first in window && last in window) return
        wanted.value = first..last
    }

    /** Highlights [document] until cancelled (its tab closes). */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(document: Document, store: GrammarStore) {
        val grammar = store.await(spec) ?: return
        // Parser and tree are used by one thread at a time; the UI thread only reads the published highlights.
        val worker = Dispatchers.Default.limitedParallelism(1)
        val highlighter = withContext(worker) { TreeSitterHighlighter(grammar) }
        try {
            val visible = shown.map { it > 0 }.distinctUntilChanged()
            val requests =
                combine(snapshotFlow { document.version }, wanted, visible) { _, range, isShown ->
                    range.takeIf { isShown }
                }
            requests.conflate().collect { range ->
                if (range == null) {
                    // Hidden: the tree is freed, the colors kept for when the document is shown again.
                    withContext(worker) { highlighter.release() }
                } else {
                    highlights = highlightsOf(document.lines.toList(), range, highlighter, worker)
                }
                isParsed = range != null && highlights != null
            }
        } finally {
            withContext(NonCancellable + worker) { highlighter.close() }
        }
    }

    // The colors of the lines around [range], once [lines] parsed; `null` for a text too large.
    private suspend fun highlightsOf(
        lines: List<String>,
        range: IntRange,
        highlighter: TreeSitterHighlighter,
        worker: CoroutineDispatcher,
    ): SyntaxHighlights? {
        if (lines.sumOf { it.length + 1 } > MAX_CHARACTERS) {
            withContext(worker) { highlighter.release() }
            return null
        }
        val margin = range.last - range.first + 1
        val window = maxOf(0, range.first - margin)..minOf(lines.lastIndex, range.last + margin)
        val spans =
            withContext(worker) {
                highlighter.update(lines)
                highlighter.highlight(window.first, window.last + 1)
            }
        return SyntaxHighlights(spans.mapValues { (index, line) -> lines[index] to line }, window)
    }

    private companion object {
        const val INITIAL_LINES = 100

        // Beyond this, a document keeps the line lexer: parsing it would take seconds and a lot of memory.
        const val MAX_CHARACTERS = 16 * 1024 * 1024
    }
}
