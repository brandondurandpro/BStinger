package org.example.kotlineditor.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.treesitter.LineSpans

private const val LINE_CAPACITY = 2048
private const val BLAME_CAPACITY = 256

/**
 * Highlighted and laid out lines of an editor drawing its rows itself, kept by content: scrolling back to lines
 * already seen, or between two lines with the same text, lays nothing out again. One cache per text style.
 */
internal class LineRenderCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val blameStyle: TextStyle = style,
    lineCapacity: Int = LINE_CAPACITY,
) {
    private data class LineKey(
        val text: String,
        val language: Language,
        val startsInComment: Boolean,
        val placeholder: String?,
        val spans: LineSpans?,
    )

    private val lines = LruMap<LineKey, TextLayoutResult>(lineCapacity)

    // As many numbers as lines: scrolling back over the same lines must not lay them out again.
    private val numbers = LruMap<Int, TextLayoutResult>(lineCapacity)

    // Consecutive lines of a commit share their annotation.
    private val blames = LruMap<String, TextLayoutResult>(BLAME_CAPACITY)

    val size: Int get() = lines.size

    fun line(source: LineSource): TextLayoutResult =
        lines.getOrPut(source.key()) {
            measurer.measure(highlighted(source), style, softWrap = false, maxLines = 1)
        }

    private fun LineSource.key() = LineKey(text, language, startsInComment, placeholder, spans)

    /** Line number [number] (one-based), without color: the caret line draws it brighter. */
    fun number(number: Int): TextLayoutResult =
        numbers.getOrPut(number) { measurer.measure(number.toString(), style, softWrap = false, maxLines = 1) }

    /** Blame annotation ("author date") of a line. */
    fun blame(text: String): TextLayoutResult =
        blames.getOrPut(text) { measurer.measure(text, blameStyle, softWrap = false, maxLines = 1) }
}

internal class LruMap<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
