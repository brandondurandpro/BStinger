package org.example.kotlineditor.syntax.treesitter

/**
 * Colors the code of another language a document holds, as a `.vue` file holds TypeScript in its script, CSS in its
 * style and expressions in its template. Each language sees the document with everything else blanked, so that its
 * lines and columns are those of the document and its colors are painted straight over the document's own.
 * Not thread-safe: used from the worker of its [SyntaxSession].
 */
internal class InjectedHighlighters(private val store: GrammarStore) : AutoCloseable {
    private val highlighters = HashMap<String, TreeSitterHighlighter?>()

    /** [spans] of the lines [window] of [lines], with the colors of the [injections] painted over them. */
    suspend fun overlay(
        spans: Map<Int, LineSpans>,
        lines: List<String>,
        injections: List<Injection>,
        window: IntRange,
    ): Map<Int, LineSpans> {
        if (injections.isEmpty()) return spans
        val starts = lineStarts(lines)
        val result = HashMap(spans)
        for ((language, parts) in injections.groupBy { it.language }) {
            val highlighter = highlighterOf(language) ?: continue
            highlighter.update(blanked(lines, starts, parts))
            val inner = highlighter.highlight(window.first, window.last + 1)
            for ((index, innerSpans) in inner) {
                val line = lines.getOrNull(index) ?: continue
                val allowed = allowedOn(index, line.length, starts, parts)
                val base = result[index] ?: LineSpans.EMPTY
                result[index] = overlaid(base, innerSpans, allowed, line.length)
            }
        }
        return result
    }

    private suspend fun highlighterOf(language: String): TreeSitterHighlighter? =
        highlighters.getOrPut(language) {
            val spec = GRAMMAR_CATALOG.firstOrNull { it.id == language }
            val grammar = spec?.let { store.await(it) }
            grammar?.let { TreeSitterHighlighter(it) }
        }

    private fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size)
        var offset = 0
        lines.forEachIndexed { index, line ->
            starts[index] = offset
            offset += line.length + 1
        }
        return starts
    }

    // The lines with only the characters of [parts] left, and a `;` after each part so that the expressions of a
    // template are separate statements; the rest is blank (tabs stay, they are blank for the parser too).
    private fun blanked(lines: List<String>, starts: IntArray, parts: List<Injection>): List<String> =
        lines.mapIndexed { index, line ->
            val chars = CharArray(line.length) { ' ' }
            val lineStart = starts[index]
            for (part in parts) {
                val from = maxOf(part.start, lineStart)
                val to = minOf(part.end, lineStart + line.length)
                for (offset in from until to) chars[offset - lineStart] = line[offset - lineStart]
                val after = part.end - lineStart
                if (after in chars.indices && part.end < lineStart + line.length + 1) chars[after] = ';'
            }
            String(chars)
        }

    // The columns of line [index] that belong to [parts].
    private fun allowedOn(index: Int, length: Int, starts: IntArray, parts: List<Injection>): List<IntRange> {
        val lineStart = starts[index]
        return parts.mapNotNull { part ->
            val from = maxOf(part.start, lineStart) - lineStart
            val to = minOf(part.end, lineStart + length) - lineStart
            if (from < to) from until to else null
        }
    }

    override fun close() {
        highlighters.values.forEach { it?.close() }
        highlighters.clear()
    }
}
