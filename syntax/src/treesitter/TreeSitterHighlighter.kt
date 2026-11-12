package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.InputEdit
import io.github.treesitter.jtreesitter.InputEncoding
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Point
import io.github.treesitter.jtreesitter.QueryCursor
import io.github.treesitter.jtreesitter.QueryMatch
import io.github.treesitter.jtreesitter.Tree
import java.util.AbstractMap
import java.util.stream.Stream

/**
 * The syntax tree of one document, parsed again incrementally after each change ([update]), and the colors of the
 * lines asked for ([highlight]). Not thread-safe: one coroutine at a time uses it (see [SyntaxSession]).
 *
 * The text is given to tree-sitter in UTF-16, the encoding of Kotlin strings, so that a byte offset is twice a
 * character offset. It is read from the lines themselves, a chunk at a time, rather than joined into one string:
 * tree-sitter then copies only what it reads again, and the tree keeps no copy of the text (the query predicates are
 * checked against the lines, see [QueryPredicates]).
 *
 * The tree lives in native memory, about 2 KB per line: [release] frees it while the document is not shown.
 */
internal class TreeSitterHighlighter(private val grammar: LoadedGrammar) : AutoCloseable {
    private var tree: Tree? = null
    private var lines: List<String> = emptyList()

    // Character offset of the start of each line of `lines` in the parsed text.
    private var lineStarts = IntArray(0)
    private var textLength = 0

    /** Whether a tree is held, that is the text was parsed since the last [release]. */
    val isParsed: Boolean get() = tree != null

    /** Parses [newLines], reusing the previous tree for the lines that did not change. */
    fun update(newLines: List<String>) {
        val old = tree
        if (old != null && isSameText(newLines)) return
        val starts = IntArray(newLines.size)
        var offset = 0
        newLines.forEachIndexed { index, line ->
            starts[index] = offset
            offset += line.length + 1
        }
        val newLength = maxOf(0, offset - 1)
        if (old != null) old.edit(edit(newLines, starts, newLength))
        val source = LineSource(newLines, starts, newLength)
        // A parser for each parse: jtreesitter keeps what the callback returns until the parser is closed.
        val parsed =
            Parser(grammar.language).use { parser ->
                parser.parse(source::read, InputEncoding.UTF_16LE, old, null).orElse(null)
            }
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
        CaptureMemory().use { memory ->
            QueryCursor(grammar.query).use { cursor ->
                for (stretch in coloredStretches(lines, lineStarts, first, last)) {
                    cursor.setByteRange(stretch.first * 2, stretch.last * 2)
                    color(cursor.findCaptures(tree.rootNode, memory, QueryCursor.Options()), memory, builders, first)
                }
            }
        }
        return builders.withIndex().associate { (offset, builder) -> first + offset to builder.build() }
    }

    // Adds the colors of [captures] to the [builders] of the lines from [first]. Each capture is read before the
    // next one is asked for: its native memory is used again.
    private fun color(
        captures: Stream<AbstractMap.SimpleImmutableEntry<Int, QueryMatch>>,
        memory: CaptureMemory,
        builders: Array<LineSpanBuilder>,
        first: Int,
    ) {
        captures.forEach { entry ->
            val match = entry.value
            val capture = match.captures()[entry.key]
            val style = grammar.styleOf(capture.name())
            if (style != CaptureStyles.IGNORED && grammar.predicates.hold(match, ::textOf)) {
                val node = capture.node()
                addCapture(builders, first, node.startByte / 2, node.endByte / 2, match.patternIndex(), style)
            }
            memory.recycle()
        }
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
    private fun lineOf(offset: Int): Int = lineAt(lineStarts, offset)

    // The text between two character offsets, for the query predicates: most nodes they test are within a line.
    private fun textOf(start: Int, end: Int): String {
        val line = lineOf(start)
        val lineStart = lineStarts[line]
        val text = lines[line]
        if (end - lineStart <= text.length) return text.substring(start - lineStart, end - lineStart)
        return buildString {
            var index = line
            var from = start - lineStart
            while (length < end - start && index < lines.size) {
                if (index > line) append('\n')
                append(lines[index], from, minOf(lines[index].length, from + end - start - length))
                from = 0
                index++
            }
        }
    }

    private fun isSameText(newLines: List<String>): Boolean {
        if (newLines.size != lines.size) return false
        return newLines.indices.all { newLines[it] == lines[it] }
    }

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

    /**
     * The code of another language inside the document, by character offsets: what a `.vue` file holds besides its
     * markup (see [Injection]). Empty for a grammar that has none.
     */
    fun injections(): List<Injection> {
        val root = tree?.rootNode
        return if (grammar.spec.id == VUE && root != null) VueInjections.find(root, ::textOf) else emptyList()
    }

    /** Frees the tree; the next [update] parses the whole text again. */
    fun release() {
        tree?.close()
        tree = null
        lines = emptyList()
        lineStarts = IntArray(0)
        textLength = 0
    }

    override fun close() = release()

    // The text given to tree-sitter: from a character offset, the rest of its line and the next ones, up to a chunk.
    private class LineSource(val lines: List<String>, val starts: IntArray, val length: Int) {
        private val chunk = StringBuilder()

        @Suppress("UnusedParameter") // The callback of jtreesitter also gives the point of the offset.
        fun read(offset: Int, point: Point): String? {
            val start = offset / 2
            if (start >= length) return null
            chunk.setLength(0)
            var line = lineAt(starts, start)
            var from = start - starts[line]
            while (line < lines.size && chunk.length < CHUNK_CHARACTERS) {
                chunk.append(lines[line], from, lines[line].length)
                if (line < lines.lastIndex) chunk.append('\n')
                from = 0
                line++
            }
            return chunk.toString()
        }
    }

    private companion object {
        const val VUE = "vue"

        // Characters given to tree-sitter at once: lines are short, and each read crosses into native code.
        const val CHUNK_CHARACTERS = 4_096
    }
}

private fun endPoint(text: List<String>): Point =
    if (text.isEmpty()) Point(0, 0) else Point(text.size - 1, text.last().length * 2)

// The characters of lines [first] until [last] of [lines] the query runs over, as `start..end` offsets ([starts]:
// where each line starts): all of them, but for a minified line only the ones that are colored. Run over its
// million characters, the query made hundreds of thousands of captures (seconds, gigabytes allocated) for each
// screen scrolled to.
private fun coloredStretches(lines: List<String>, starts: IntArray, first: Int, last: Int): List<IntRange> {
    val stretches = ArrayList<IntRange>()
    var start = starts[first]
    for (line in first until last) {
        val length = lines[line].length
        if (length > MAX_COLORED_COLUMNS) {
            stretches += start..starts[line] + MAX_COLORED_COLUMNS
            start = starts[line] + length
        }
    }
    val end = starts[last - 1] + lines[last - 1].length
    if (end > start || stretches.isEmpty()) stretches += start..end
    return stretches
}

// Colors stop there on very long lines (minified files): nobody reads that far, and it bounds the work.
private const val MAX_COLORED_COLUMNS = 10_000

// The line holding character `offset`, given the offset where each line starts.
private fun lineAt(starts: IntArray, offset: Int): Int {
    val found = starts.binarySearch(offset)
    return if (found >= 0) found else -found - 2
}
