package org.example.kotlineditor.syntax.treesitter

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
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

    private val wanted = MutableStateFlow(0 until INITIAL_LINES)

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
            combine(snapshotFlow { document.version }, wanted) { _, range -> range }
                .conflate()
                .collect { range ->
                    val lines = document.lines.toList()
                    highlights =
                        if (lines.sumOf { it.length + 1 } > MAX_CHARACTERS) {
                            null
                        } else {
                            val margin = range.last - range.first + 1
                            val window = maxOf(0, range.first - margin)..minOf(lines.lastIndex, range.last + margin)
                            val spans =
                                withContext(worker) {
                                    highlighter.update(lines)
                                    highlighter.highlight(window.first, window.last + 1)
                                }
                            SyntaxHighlights(spans.mapValues { (index, line) -> lines[index] to line }, window)
                        }
                }
        } finally {
            withContext(NonCancellable + worker) { highlighter.close() }
        }
    }

    private companion object {
        const val INITIAL_LINES = 100

        // Beyond this, a document keeps the line lexer: parsing it would take seconds and a lot of memory.
        const val MAX_CHARACTERS = 16 * 1024 * 1024
    }
}
