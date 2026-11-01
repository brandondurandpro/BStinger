package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.InputEdit
import io.github.treesitter.jtreesitter.InputEncoding
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Point
import io.github.treesitter.jtreesitter.QueryCursor
import io.github.treesitter.jtreesitter.Tree

/**
 * The syntax tree of one document, parsed again incrementally after each change ([update]), and the colors of the
 * lines asked for ([highlight]). Not thread-safe: one coroutine at a time uses it (see [SyntaxSession]).
 *
 * The text is given to tree-sitter in UTF-16, the encoding of Kotlin strings, so that a byte offset is twice a
 * character offset and nothing is converted.
 */
internal class TreeSitterHighlighter(private val grammar: LoadedGrammar) : AutoCloseable {
    private val parser = Parser(grammar.language)
    private var tree: Tree? = null
    private var lines: List<String> = emptyList()

    // Character offset of the start of each line of `lines` in the parsed text.
    private var lineStarts = IntArray(0)
    private var textLength = 0

    /** Parses [newLines], reusing the previous tree for the lines that did not change. */
    fun update(newLines: List<String>) {
        val old = tree
        if (old != null && newLines.size == lines.size && sameLines(newLines)) return
        val starts = IntArray(newLines.size)
        var offset = 0
        newLines.forEachIndexed { index, line ->
            starts[index] = offset
            offset += line.length + 1
        }
        val newLength = maxOf(0, offset - 1)
        if (old != null) old.edit(edit(newLines, starts, newLength))
        val text = newLines.joinToString("\n")
        val parsed = parser.parse(text, InputEncoding.UTF_16LE, old).orElse(null)
        old?.close()
        tree = parsed
        lines = newLines
        lineStarts = starts
        textLength = newLength
    }

    /** Colors of lines [from] until [to] of the last text given to [update], by line index. */
    fun highlight(from: Int, to: Int): Map<Int, LineSpans> {
        val tree = tree
        val first = from.coerceIn(0, lines.size)
        val last = to.coerceIn(first, lines.size)
        if (tree == null || first == last) return emptyMap()
        val builders = Array(last - first) { LineSpanBuilder(minOf(lines[first + it].length, MAX_COLORED_COLUMNS)) }
        val startChar = lineStarts[first]
        val endChar = lineStarts[last - 1] + lines[last - 1].length
        QueryCursor(grammar.query).use { cursor ->
            cursor.setByteRange(startChar * 2, endChar * 2)
            cursor.findCaptures(tree.rootNode).forEach { entry ->
                val match = entry.value
                val capture = match.captures()[entry.key]
                val style = grammar.styleOf(capture.name())
                if (style != CaptureStyles.IGNORED) {
                    val node = capture.node()
                    addCapture(builders, first, node.startByte / 2, node.endByte / 2, match.patternIndex(), style)
                }
            }
        }
        return builders.withIndex().associate { (offset, builder) -> first + offset to builder.build() }
    }

    private fun addCapture(
        builders: Array<LineSpanBuilder>,
        first: Int,
        start: Int,
        end: Int,
        pattern: Int,
        style: Int,
    ) {
        var line = maxOf(lineOf(start), first)
        val last = minOf(lineOf(maxOf(start, end - 1)), first + builders.size - 1)
        while (line <= last) {
            val lineStart = lineStarts[line]
            builders[line - first].add(start - lineStart, end - lineStart, pattern, style)
            line++
        }
    }

    // The line holding character `offset` of the text.
    private fun lineOf(offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }

    private fun sameLines(newLines: List<String>): Boolean = newLines.indices.all { newLines[it] == lines[it] }

    // The change from `lines` to `newLines`, as whole lines: those between the common first and last lines.
    private fun edit(newLines: List<String>, newStarts: IntArray, newLength: Int): InputEdit {
        val common = minOf(lines.size, newLines.size)
        val prefix = (0 until common).firstOrNull { lines[it] != newLines[it] } ?: common
        val suffix =
            (0 until common - prefix).firstOrNull { lines[lines.size - 1 - it] != newLines[newLines.size - 1 - it] }
                ?: common - prefix
        // Lines added or removed at the end: the change starts at the end of the last common line, its `\n`.
        val atEnd = prefix > 0 && (prefix == lines.size || prefix == newLines.size)
        val start = if (atEnd) lineStarts[prefix - 1] + lines[prefix - 1].length else lineStarts[prefix]
        val startPoint = if (atEnd) Point(prefix - 1, lines[prefix - 1].length * 2) else Point(prefix, 0)
        val oldEnd = if (suffix > 0) lineStarts[lines.size - suffix] else textLength
        val oldEndPoint = if (suffix > 0) Point(lines.size - suffix, 0) else endPoint(lines)
        val newEnd = if (suffix > 0) newStarts[newLines.size - suffix] else newLength
        val newEndPoint = if (suffix > 0) Point(newLines.size - suffix, 0) else endPoint(newLines)
        return InputEdit(start * 2, oldEnd * 2, newEnd * 2, startPoint, oldEndPoint, newEndPoint)
    }

    private fun endPoint(text: List<String>): Point =
        if (text.isEmpty()) Point(0, 0) else Point(text.size - 1, text.last().length * 2)

    override fun close() {
        tree?.close()
        tree = null
        parser.close()
    }

    private companion object {
        // Colors stop there on very long lines (minified files): nobody reads that far, and it bounds the work.
        const val MAX_COLORED_COLUMNS = 10_000
    }
}
