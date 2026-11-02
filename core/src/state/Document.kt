package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.treesitter.SyntaxSession
import org.example.kotlineditor.syntax.treesitter.SyntaxSource

/**
 * Editable text buffer backed by a snapshot list of lines, so that Compose only re-renders what changed.
 * Every mutation goes through [replace], which records the inverse operation for undo/redo. Navigation,
 * editing and search actions are extensions, see `DocumentNavigation.kt`, `DocumentEditing.kt`, ...
 */
@Stable
class Document(initialLines: List<String>, val language: Language, val lineSeparator: String = "\n") : SyntaxSource {
    val lines = mutableStateListOf<String>().apply { addAll(initialLines.ifEmpty { listOf("") }) }

    var caret by mutableStateOf(TextPos(0, 0))
        private set

    /** Other end of the selection, `null` when nothing is selected. */
    var anchor by mutableStateOf<TextPos?>(null)
        private set

    /** Incremented on every edit; lets observers (save state, highlighting) react cheaply. */
    override var version by mutableIntStateOf(0)
        private set

    var savedVersion by mutableIntStateOf(0)
        private set

    /** Widest line in screen cells; widened on edits, never shrunk (cheap and good enough for scrolling). */
    var maxLineLength by mutableIntStateOf(initialLines.maxOfOrNull { visualColumn(it, it.length) } ?: 0)
        private set

    /** Folded regions; its regions are recomputed by [EditorState] after edits. */
    val folding = FoldingModel(this)

    /** Precomputed block-comment state per line, see [org.example.kotlineditor.syntax.SyntaxHighlighter]. */
    var commentStates by mutableStateOf<BooleanArray?>(null)

    /** Tree-sitter highlighting, when the language has a grammar; the line lexer colors the rest. */
    var syntax: SyntaxSession? = null
        set

    /**
     * Told of every change of the text (typing, undo, reload...), with the range it replaced in the text before:
     * the language servers' copy of the document follows it (see [DocumentSync]).
     */
    var editListener: ((TextChange) -> Unit)? = null

    /** Selections before each Ctrl+W, for Ctrl+Shift+W. */
    val selectionHistory = ArrayDeque<Pair<TextPos?, TextPos>>()

    val isModified: Boolean get() = version != savedVersion

    val hasSelection: Boolean get() = anchor != null && anchor != caret

    val lineCount: Int get() = lines.size

    // Column the caret tries to keep when moving vertically.
    private var preferredVisualColumn = 0
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    private class Edit(
        val start: TextPos,
        val removed: String,
        val inserted: String,
        val caretBefore: TextPos,
        val anchorBefore: TextPos?,
        val caretAfter: TextPos,
    ) {
        // Consecutive typing of the same kind of characters is one undo step, like IntelliJ.
        fun absorbs(at: TextPos, removedNow: String, typed: String): Boolean {
            val oneChar = removedNow.isEmpty() && typed.length == 1 && typed != "\n"
            val wasTyping = removed.isEmpty() && inserted.isNotEmpty() && '\n' !in inserted
            val sameKind = inserted.lastOrNull()?.isWordChar() == typed.firstOrNull()?.isWordChar()
            val continues = sameKind && endOf(start, inserted) == at
            return oneChar && wasTyping && continues
        }
    }

    override fun snapshotLines(): List<String> = lines.toList()

    fun text(): String = lines.joinToString(lineSeparator)

    fun markSaved() {
        savedVersion = version
    }

    fun selectionRange(): Pair<TextPos, TextPos>? {
        val a = anchor
        return when {
            a == null || a == caret -> null
            a < caret -> a to caret
            else -> caret to a
        }
    }

    fun select(start: TextPos, end: TextPos) {
        anchor = clamp(start)
        moveTo(end, extendSelection = true)
    }

    fun moveTo(pos: TextPos, extendSelection: Boolean = false) {
        val target = clamp(pos)
        anchor = if (extendSelection) anchor ?: caret else null
        caret = target
        preferredVisualColumn = visualColumn(lines[target.line], target.column)
    }

    /** Moves to [targetLine] keeping the column the caret had before vertical moves started. */
    fun moveVerticallyTo(targetLine: Int, extend: Boolean = false) {
        val line = targetLine.coerceIn(0, lines.lastIndex)
        val keep = preferredVisualColumn
        moveTo(TextPos(line, columnForVisual(lines[line], keep)), extend)
        preferredVisualColumn = keep
    }

    /** Replaces the text between [start] and [end] with [text] (which may contain `\n`) and puts the caret after it. */
    fun replace(start: TextPos, end: TextPos, text: String) {
        val s = clamp(start)
        val e = clamp(end)
        val removed = textBetween(s, e)
        if (removed.isEmpty() && text.isEmpty()) return
        val caretBefore = caret
        val anchorBefore = anchor
        val after = applyRaw(s, e, text)
        anchor = null
        caret = after
        preferredVisualColumn = visualColumn(lines[after.line], after.column)
        redoStack.clear()
        folding.onEdit()
        selectionHistory.clear()
        val last = undoStack.lastOrNull()
        if (last != null && last.absorbs(s, removed, text)) {
            undoStack[undoStack.lastIndex] =
                Edit(last.start, "", last.inserted + text, last.caretBefore, last.anchorBefore, after)
        } else {
            undoStack.addLast(Edit(s, removed, text, caretBefore, anchorBefore, after))
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        version++
    }

    fun undo(): Boolean {
        val edit = undoStack.removeLastOrNull()
        if (edit != null) {
            applyRaw(edit.start, endOf(edit.start, edit.inserted), edit.removed)
            redoStack.addLast(edit)
            folding.onEdit()
            anchor = edit.anchorBefore
            caret = edit.caretBefore
            version++
        }
        return edit != null
    }

    fun redo(): Boolean {
        val edit = redoStack.removeLastOrNull()
        if (edit != null) {
            applyRaw(edit.start, endOf(edit.start, edit.removed), edit.inserted)
            undoStack.addLast(edit)
            folding.onEdit()
            anchor = null
            caret = edit.caretAfter
            version++
        }
        return edit != null
    }

    private fun applyRaw(start: TextPos, end: TextPos, text: String): TextPos {
        editListener?.invoke(TextChange(start.line, start.column, end.line, end.column, text))
        val prefix = lines[start.line].substring(0, start.column)
        val suffix = lines[end.line].substring(end.column)
        val parts = text.split('\n')
        val replacement =
            parts.mapIndexed { index, part ->
                val head = if (index == 0) prefix else ""
                val tail = if (index == parts.lastIndex) suffix else ""
                head + part + tail
            }
        if (start.line == end.line && replacement.size == 1) {
            lines[start.line] = replacement[0]
        } else {
            // Remove then insert in bulk: a single snapshot write instead of one per line.
            lines.removeRange(start.line, end.line + 1)
            lines.addAll(start.line, replacement)
        }
        maxLineLength = maxOf(maxLineLength, replacement.maxOf { visualColumn(it, it.length) })
        return TextPos(start.line + parts.lastIndex, replacement.last().length - suffix.length)
    }

    private companion object {
        const val MAX_UNDO = 1000
    }
}

// Position right after `text` inserted at `start`.
private fun endOf(start: TextPos, text: String): TextPos {
    val newlines = text.count { it == '\n' }
    return if (newlines == 0) {
        TextPos(start.line, start.column + text.length)
    } else {
        TextPos(start.line + newlines, text.length - text.lastIndexOf('\n') - 1)
    }
}
