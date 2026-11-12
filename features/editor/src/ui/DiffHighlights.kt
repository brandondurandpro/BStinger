package org.example.kotlineditor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.highlightChunks

// One side of a hunk: the indexes (in the diff) of its lines. Each hunk is parsed apart, since the lines between
// two hunks are not in the diff.
private class Side(val indexes: MutableList<Int> = ArrayList())

private class HunkSides(val old: Side = Side(), val new: Side = Side())

/**
 * The tree-sitter colors of each line of [lines], a diff of the file [fileName]: the old side (context and removed
 * lines) and the new side (context and added lines) of each hunk are parsed apart, so that each line is read in the
 * code it belongs to. `null` for a line without colors (a hunk header), and for all when the file has no grammar.
 */
suspend fun GrammarStore.highlightDiff(fileName: String, lines: List<DiffLine>): List<LineSpans?>? {
    val hunks = hunksOf(lines)
    val sides = hunks.flatMap { listOf(it.old, it.new) }
    val chunks = sides.map { side -> side.indexes.map { lines[it].text } }
    val colored = highlightChunks(fileName, chunks) ?: return null
    val spans = arrayOfNulls<LineSpans>(lines.size)
    sides.forEachIndexed { number, side ->
        side.indexes.forEachIndexed { position, index -> spans[index] = colored[number][position] }
    }
    return spans.toList()
}

private fun hunksOf(lines: List<DiffLine>): List<HunkSides> {
    val hunks = ArrayList<HunkSides>()
    var current = HunkSides()
    hunks += current
    lines.forEachIndexed { index, line ->
        when (line.kind) {
            DiffKind.Hunk -> {
                current = HunkSides()
                hunks += current
            }

            DiffKind.Context -> {
                current.old.indexes += index
                current.new.indexes += index
            }

            DiffKind.Removed -> {
                current.old.indexes += index
            }

            DiffKind.Added -> {
                current.new.indexes += index
            }
        }
    }
    return hunks
}

/** The text of a diff line colored by its tree-sitter [spans], else by the line lexer of [language]. */
internal fun diffCode(text: String, spans: LineSpans?, language: Language): AnnotatedString {
    val source = LineSource(0, text, language, false, false, false, null, spans)
    return highlighted(source)
}

/** Characters shown of a line of a diff: more than the widest screen holds. */
internal const val MAX_DIFF_LINE_CHARACTERS = 2_048

/** How the code of a diff is colored: by the tree-sitter [spans] of its lines when known, else by the lexer. */
internal class DiffCode(private val language: Language, private val spans: List<LineSpans?>?) {
    fun colored(text: String, index: Int, kind: DiffKind): AnnotatedString {
        if (kind == DiffKind.Hunk) return AnnotatedString(text)
        val lineSpans = spans?.getOrNull(index)
        // A minified line: a row is not scrolled sideways, so what is beyond the widest screen is never seen, and
        // laid out whole a line of megabytes takes minutes and gigabytes.
        val tooLong = text.length > MAX_DIFF_LINE_CHARACTERS
        val shown = if (tooLong) text.substring(0, shownLength(text)) else text
        val shownSpans = if (tooLong) lineSpans?.clippedTo(shown.length) else lineSpans
        return diffCode(shown, shownSpans, language)
    }

    // The characters shown of a line too long, cut after a whole character.
    private fun shownLength(text: String): Int {
        val splitsPair = Character.isHighSurrogate(text[MAX_DIFF_LINE_CHARACTERS - 1])
        return if (splitsPair) MAX_DIFF_LINE_CHARACTERS - 1 else MAX_DIFF_LINE_CHARACTERS
    }
}

// The code of a changed line keeps the colors of its syntax; the marker and the background tell it was changed.
internal fun codeColorOf(kind: DiffKind): Color = if (kind == DiffKind.Hunk) IdeColors.TextSecondary else IdeColors.Code
