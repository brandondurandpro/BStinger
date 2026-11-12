package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable

/**
 * The hints drawn in a line between its characters (inlay hints: `name:` before an argument): [labels] at
 * [columns], each one drawn before the character of its column, one screen cell per character of its label. They
 * are not text of the document: the caret, the selection and the clicks go around them.
 */
@Immutable
class LineInlays(
    /** Column of the character each hint is drawn before, in increasing order. */
    val columns: IntArray,
    /** What each hint shows. */
    val labels: List<String>,
) {
    val size: Int get() = columns.size

    override fun equals(other: Any?): Boolean =
        other is LineInlays && columns.contentEquals(other.columns) && labels == other.labels

    override fun hashCode(): Int = 31 * columns.contentHashCode() + labels.hashCode()

    /** Screen cells of the hints drawn before the character at [column], those at [column] included. */
    fun cellsBefore(column: Int): Int {
        var cells = 0
        var index = 0
        while (index < columns.size && columns[index] <= column) {
            cells += labels[index].length
            index++
        }
        return cells
    }

    /** Screen cells of the hints drawn exactly before the character at [column]. */
    fun cellsAt(column: Int): Int {
        var cells = 0
        for (index in columns.indices) {
            if (columns[index] == column) cells += labels[index].length
        }
        return cells
    }
}

/** How the inlay hints of the language server (`name:` before an argument) are shown. */
enum class InlayHintsMode {
    /** Not shown. */
    Off,

    /** The line of the caret, hints included, in a small popup above it: the code itself does not move. */
    Popup,

    /** In the code, between its characters, like IntelliJ. */
    Inline,
    ;

    companion object {
        /** The mode saved as [saved] in the preferences, [Popup] when none or unknown. */
        fun fromSaved(saved: String?): InlayHintsMode = entries.firstOrNull { it.name == saved } ?: Popup
    }
}

/** Column on screen of [column] of the line [line] of the document: tabs, and the hints drawn before it. */
fun Document.visualColumnOf(line: Int, column: Int): Int = visualColumn(lines[line], column, inlays[line])

/** Inverse of [visualColumnOf]: the column of [line] closest to the screen cell [visual]. */
fun Document.columnOfVisual(line: Int, visual: Int): Int = columnForVisual(lines[line], visual, inlays[line])

/** A line as the editor draws it: its [text] and the [inlays] between its characters, in screen cells. */
class ScreenLine(val text: String, val inlays: LineInlays? = null) {
    // Whether a column is its cell: no tab, no hint. Looked for once, however many columns are asked for (the
    // thousands of occurrences of a name on a minified line, each of which searched the line from its start).
    private val plain by lazy(LazyThreadSafetyMode.NONE) { inlays == null && '\t' !in text }

    /** Cell after the text and all its hints. */
    val end: Int get() = startOf(text.length)

    /** Cell where the character at [column] starts (after the hints drawn before it). */
    fun startOf(column: Int): Int {
        val clamped = column.coerceIn(0, text.length)
        return if (plain) clamped else visualColumn(text, clamped, inlays)
    }

    /** Cell where a range ending before [column] ends: the hints drawn before that character are not in it. */
    fun endOf(column: Int): Int {
        val clamped = column.coerceIn(0, text.length)
        return startOf(clamped) - (inlays?.cellsAt(clamped) ?: 0)
    }
}

/** Line [index] of the document as drawn. */
fun Document.screenLine(index: Int): ScreenLine = ScreenLine(lines[index], inlays[index])
