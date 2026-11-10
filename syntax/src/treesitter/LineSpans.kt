package org.example.kotlineditor.syntax.treesitter

import androidx.compose.runtime.Immutable

/**
 * The colored ranges of one line, in characters of the line (before tabs are expanded): `[starts[i], ends[i])` has
 * the color `SyntaxColor.entries[styles[i]]`. Ranges are sorted and do not overlap. Compared by content, so that
 * the laid out line can be reused while its colors stay the same.
 */
@Immutable
class LineSpans(val starts: IntArray, val ends: IntArray, val styles: IntArray) {
    val size: Int get() = starts.size

    private val hash =
        listOf(starts, ends, styles).fold(0) { hash, array -> hash * HASH_MULTIPLIER + array.contentHashCode() }

    /** These spans cut at [length], for a line that got shorter since they were computed. */
    fun clippedTo(length: Int): LineSpans {
        if (size == 0 || ends[size - 1] <= length) return this
        val kept = starts.count { it < length }
        return LineSpans(
            starts.copyOf(kept),
            IntArray(kept) { minOf(ends[it], length) },
            styles.copyOf(kept),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (other !is LineSpans || hash != other.hash) return false
        val sameStarts = starts.contentEquals(other.starts)
        val sameEnds = ends.contentEquals(other.ends)
        return sameStarts && sameEnds && styles.contentEquals(other.styles)
    }

    override fun hashCode(): Int = hash

    override fun toString(): String =
        (0 until size).joinToString(prefix = "LineSpans(", postfix = ")") { "${starts[it]}-${ends[it]}:${styles[it]}" }

    companion object {
        private const val HASH_MULTIPLIER = 31
        val EMPTY = LineSpans(IntArray(0), IntArray(0), IntArray(0))
    }
}

/**
 * Collects the captures of one line and resolves their overlaps as tree-sitter's highlighter does: a node inside
 * another one takes its own style over the outer one's, and among captures of the same range the last pattern of the
 * query wins (queries go from the general `(identifier) @variable` to the specific).
 */
internal class LineSpanBuilder(private val length: Int) {
    private var count = 0
    private var starts = IntArray(INITIAL)
    private var ends = IntArray(INITIAL)
    private var patterns = IntArray(INITIAL)
    private var styles = IntArray(INITIAL)

    fun add(start: Int, end: Int, pattern: Int, style: Int) {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        if (e <= s) return
        if (count == starts.size) grow()
        starts[count] = s
        ends[count] = e
        patterns[count] = pattern
        styles[count] = style
        count++
    }

    fun build(): LineSpans {
        if (count == 0) return LineSpans.EMPTY
        // Painted in order: outer ranges first (inner ones paint over them), equal ranges by pattern.
        val order =
            (0 until count).sortedWith(
                compareBy<Int>({ starts[it] }, { -ends[it] }, { patterns[it] }),
            )
        val painted = IntArray(length) { CaptureStyles.NONE }
        for (i in order) painted.fill(styles[i], starts[i], ends[i])
        return spansOf(painted)
    }

    private fun grow() {
        starts = starts.copyOf(count * 2)
        ends = ends.copyOf(count * 2)
        patterns = patterns.copyOf(count * 2)
        styles = styles.copyOf(count * 2)
    }

    private companion object {
        const val INITIAL = 16
    }
}

/** The runs of the same color of [painted], one style per character of a line. */
internal fun spansOf(painted: IntArray): LineSpans {
    val runStarts = ArrayList<Int>()
    val runEnds = ArrayList<Int>()
    val runStyles = ArrayList<Int>()
    var i = 0
    while (i < painted.size) {
        val style = painted[i]
        var j = i + 1
        while (j < painted.size && painted[j] == style) j++
        if (style != CaptureStyles.NONE) {
            runStarts += i
            runEnds += j
            runStyles += style
        }
        i = j
    }
    return LineSpans(runStarts.toIntArray(), runEnds.toIntArray(), runStyles.toIntArray())
}

/**
 * [base] with the colors of [inner] painted over it where [allowed] (ranges of the line), for a line of [length]
 * characters: the code of another language embedded in the line.
 */
internal fun overlaid(base: LineSpans, inner: LineSpans, allowed: List<IntRange>, length: Int): LineSpans {
    val painted = IntArray(length) { CaptureStyles.NONE }
    for (i in 0 until base.size) {
        val end = base.ends[i].coerceAtMost(length)
        painted.fill(base.styles[i], base.starts[i].coerceAtMost(end), end)
    }
    for (i in 0 until inner.size) {
        for (range in allowed) {
            val from = maxOf(inner.starts[i], range.first)
            val to = minOf(inner.ends[i], range.last + 1, length)
            if (from < to) painted.fill(inner.styles[i], from, to)
        }
    }
    return spansOf(painted)
}
