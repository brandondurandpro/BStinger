package org.example.kotlineditor.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import org.example.kotlineditor.syntax.Language

private const val LINE_CAPACITY = 2048
private const val NUMBER_CAPACITY = 512

/**
 * Highlighted and laid out lines of an editor drawing its rows itself, kept by content: scrolling back to lines
 * already seen, or between two lines with the same text, lays nothing out again. One cache per text style.
 */
internal class LineRenderCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    lineCapacity: Int = LINE_CAPACITY,
) {
    private data class LineKey(
        val text: String,
        val language: Language,
        val startsInComment: Boolean,
        val placeholder: String?,
    )

    private val lines = LruMap<LineKey, TextLayoutResult>(lineCapacity)
    private val numbers = LruMap<Int, TextLayoutResult>(NUMBER_CAPACITY)

    val size: Int get() = lines.size

    fun line(source: LineSource): TextLayoutResult =
        lines.getOrPut(LineKey(source.text, source.language, source.startsInComment, source.placeholder)) {
            measurer.measure(highlighted(source), style, softWrap = false, maxLines = 1)
        }

    /** Line number [number] (one-based), without color: the caret line draws it brighter. */
    fun number(number: Int): TextLayoutResult =
        numbers.getOrPut(number) { measurer.measure(number.toString(), style, softWrap = false, maxLines = 1) }
}

private class LruMap<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
