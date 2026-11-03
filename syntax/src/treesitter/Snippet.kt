package org.example.kotlineditor.syntax.treesitter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line taken out of a file, such as the preview of a usage: [text], from the file named [fileName]. */
class Snippet(val fileName: String, val text: String)

// What a lone line needs before it to be read as code: PHP is only code after its opening tag.
private val OPENINGS = mapOf("php" to "<?php")

/**
 * The tree-sitter colors of [snippets], in their order: each line is parsed on its own with the grammar of its file,
 * after the opening its language needs (`<?php`), so that a line cut out of a comment or a string does not color the
 * next ones. `null` for a line whose file has no grammar, or whose grammar cannot load: the line lexer colors it.
 * Suspends while a grammar downloads.
 */
suspend fun GrammarStore.highlightSnippets(snippets: List<Snippet>): List<LineSpans?> {
    val colors = arrayOfNulls<LineSpans>(snippets.size)
    val bySpec = snippets.indices.groupBy { GrammarSpec.forFileName(snippets[it].fileName) }
    for ((spec, indices) in bySpec) {
        val grammar = spec?.let { await(it) }
        if (grammar != null) {
            val opening = OPENINGS[spec.id]
            withContext(Dispatchers.Default) {
                TreeSitterHighlighter(grammar).use { highlighter ->
                    indices.forEach { index -> colors[index] = highlighter.colorsOf(snippets[index].text, opening) }
                }
            }
        }
    }
    return colors.toList()
}

// The colors of [line] parsed alone, after [opening] when there is one.
private fun TreeSitterHighlighter.colorsOf(line: String, opening: String?): LineSpans? {
    val lines = if (opening != null) listOf(opening, line) else listOf(line)
    update(lines)
    return highlight(lines.lastIndex, lines.size)[lines.lastIndex]
}
