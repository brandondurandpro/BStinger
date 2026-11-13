package org.example.kotlineditor.syntax.treesitter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The tree-sitter colors of [chunks] of a file named [fileName], each chunk parsed on its own as a text, such as the
 * lines of one side of a diff hunk. The result has one [LineSpans] per line of each chunk, in order; `null` when the
 * file has no grammar or its grammar cannot load (the line lexer colors it). Suspends while a grammar downloads.
 */
suspend fun GrammarStore.highlightChunks(fileName: String, chunks: List<List<String>>): List<List<LineSpans>>? {
    val spec = GrammarSpec.forFileName(fileName)
    val grammar = spec?.let { await(it) } ?: return null
    return withContext(Dispatchers.Default) {
        // A highlighter for each chunk: the chunks are unrelated texts, and parsing one incrementally from the tree
        // of the previous one gives wrong or empty colors.
        chunks.map { chunk -> TreeSitterHighlighter(grammar).use { highlighter -> colorsOf(highlighter, chunk) } }
    }
}

private fun colorsOf(highlighter: TreeSitterHighlighter, chunk: List<String>): List<LineSpans> {
    if (chunk.isEmpty()) return emptyList()
    highlighter.update(chunk)
    val colored = highlighter.highlight(0, chunk.size)
    return chunk.indices.map { colored[it] ?: LineSpans.EMPTY }
}
