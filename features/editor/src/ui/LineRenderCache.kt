package org.example.kotlineditor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.example.kotlineditor.state.LineInlays
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.treesitter.LineSpans

private const val LINE_CAPACITY = 2048
private const val BLAME_CAPACITY = 256
private const val LONG_LINE_CAPACITY = 4
private const val SLICE_CAPACITY = 64

/** A line with more characters is laid out a slice at a time (see [LineRenderCache.slices]). */
internal const val LONG_LINE_CHARACTERS = 4_096

/** Characters of a slice of a long line: a few slices cover the widest screen. */
internal const val SLICE_CHARACTERS = 1_024

/** A laid out slice of a long line, which starts at the character [start] of the displayed line. */
internal class LineSlice(val start: Int, val layout: LineLayout) : AutoCloseable {
    override fun close() = layout.close()
}

/**
 * Highlighted and laid out lines of an editor drawing its rows itself, kept by content: scrolling back to lines
 * already seen, or between two lines with the same text, lays nothing out again. One cache per text style.
 *
 * The layouts own native memory ([LineLayout]): one dropped from the cache is closed at once, and all of them when
 * the editor leaves the screen ([close]). A layout is drawn in the frame it was asked for, not kept.
 */
internal class LineRenderCache(
    private val layouter: LineLayouter,
    private val blameLayouter: LineLayouter = layouter,
    lineCapacity: Int = LINE_CAPACITY,
) : AutoCloseable {
    private data class LineKey(
        val text: String,
        val language: Language,
        val startsInComment: Boolean,
        val placeholder: String?,
        val spans: LineSpans?,
        val inlays: LineInlays?,
        val semantic: LineSemantic?,
        val bracketDepth: Int,
    )

    private val lines = ClosingLruMap<LineKey, LineLayout>(lineCapacity)

    // As many numbers as lines: scrolling back over the same lines must not lay them out again.
    private data class NumberKey(val number: Int, val color: Color)

    private val numbers = ClosingLruMap<NumberKey, LineLayout>(lineCapacity)

    // Consecutive lines of a commit share their annotation.
    private val blames = ClosingLruMap<String, LineLayout>(BLAME_CAPACITY)

    // The usage hints share a few texts ("1 usage", "3 usages").
    private val hints = ClosingLruMap<String, LineLayout>(BLAME_CAPACITY)

    // The long lines on screen: their highlighted text and the slices cut of it, dropped together, so that the
    // megabytes of a line that was edited are not kept by its slices.
    private class LongLine(val text: AnnotatedString) : AutoCloseable {
        val slices = ClosingLruMap<Int, LineSlice>(SLICE_CAPACITY)

        override fun close() = slices.closeAll()
    }

    private val longLines = ClosingLruMap<LineKey, LongLine>(LONG_LINE_CAPACITY)

    val size: Int get() = lines.size

    /**
     * Whether the line of [source] is drawn by [slices] rather than [line]: a minified file has lines of a million
     * characters, which take minutes and gigabytes to lay out whole, and are wider than a layout can be.
     */
    fun isLong(source: LineSource): Boolean = source.text.length > LONG_LINE_CHARACTERS

    /**
     * The slices of the long line of [source] that hold its characters [from] until [until], in order. The code
     * font has one width, so a slice is drawn where its first character goes without the text before it.
     */
    fun slices(source: LineSource, from: Int, until: Int): List<LineSlice> {
        val line = longLines.getOrPut(source.key()) { LongLine(highlighted(source)) }
        val text = line.text
        val first = from.coerceIn(0, text.length) / SLICE_CHARACTERS
        val last = (until.coerceIn(0, text.length) + SLICE_CHARACTERS - 1) / SLICE_CHARACTERS
        return (first until last).map { number -> line.slices.getOrPut(number) { slice(text, number) } }
    }

    private fun slice(text: AnnotatedString, number: Int): LineSlice {
        val start = sliceStart(text, number)
        val end = sliceStart(text, number + 1)
        return LineSlice(start, layouter.layout(text.subSequence(start, end)))
    }

    // Where slice [number] starts: after a whole character, never between the two halves of one.
    private fun sliceStart(text: AnnotatedString, number: Int): Int {
        val start = minOf(number * SLICE_CHARACTERS, text.length)
        val splitsPair = start in 1 until text.length && Character.isLowSurrogate(text[start])
        return if (splitsPair) start + 1 else start
    }

    fun line(source: LineSource): LineLayout = lines.getOrPut(source.key()) { layouter.layout(highlighted(source)) }

    private fun LineSource.key() =
        LineKey(text, language, startsInComment, placeholder, spans, inlays, semantic, bracketDepth)

    /** Line number [number] (one-based) in [color]: the caret line draws it brighter. */
    fun number(number: Int, color: Color): LineLayout =
        numbers.getOrPut(NumberKey(number, color)) { layouter.layout(number.toString(), color) }

    /** Usage hint ("3 usages") drawn after the text of a declaration, in the code font. */
    fun hint(text: String): LineLayout = hints.getOrPut(text) { layouter.layout(text, IdeColors.InlayHint) }

    /** Blame annotation ("author date") of a line. */
    fun blame(text: String): LineLayout = blames.getOrPut(text) { blameLayouter.layout(text) }

    /** Frees the native memory of every layout, and of the layouters. */
    override fun close() {
        listOf(lines, numbers, blames, hints, longLines).forEach { it.closeAll() }
        layouter.close()
        if (blameLayouter !== layouter) blameLayouter.close()
    }
}
