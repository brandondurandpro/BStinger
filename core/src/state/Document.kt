package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.example.kotlineditor.model.FileVersion
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
@Suppress("TooManyFunctions")
class Document(initialLines: List<String>, val language: Language, val lineSeparator: String = "\n") : SyntaxSource {
    val lines = mutableStateListOf<String>().apply { addAll(initialLines.ifEmpty { listOf("") }) }

    var caret by mutableStateOf(TextPos(0, 0))
        private set

    /** Other end of the selection, `null` when nothing is selected. */
    var anchor by mutableStateOf<TextPos?>(null)
        private set

    /**
     * The other carets (Alt+click, Alt+J, column selection), most recently added first; the [caret] above is the
     * primary one. They follow every edit of the text, and a caret move that is not done for every caret
     * ([forEachCaret]) removes them.
     */
    var extraCarets by mutableStateOf<List<Caret>>(emptyList())
        private set

    /** Told of every change of the text while carets are moved for each of them, see [forEachCaret]. */
    internal var caretTracker: ((TextChange) -> Unit)? = null

    /** The word Alt+J selected at the caret: its next occurrences are whole words only, like IntelliJ. */
    internal var occurrenceWord: String? = null

    /** Incremented on every edit; lets observers (save state, highlighting) react cheaply. */
    override var version by mutableIntStateOf(0)
        private set

    var savedVersion by mutableIntStateOf(0)
        private set

    /**
     * The version of the file on the disk as the IDE last read or wrote it ([FileVersion]), `null` when that is
     * not known (the file was created by the IDE, or the disk could not be stat'ed): a save sends it, and the
     * server refuses to write when the file changed since; reads of the disk compare theirs with it.
     */
    var diskVersion: FileVersion? = null

    /** Another program changed the file while it had unsaved edits: it is not saved until the user chooses. */
    var diskConflict by mutableStateOf(false)

    /** Why the last save failed (a read-only file, an encoding...), until a save works. */
    var saveError: String? by mutableStateOf(null)

    /** Widest line in screen cells; widened on edits, never shrunk (cheap and good enough for scrolling). */
    var maxLineLength by mutableIntStateOf(initialLines.maxOfOrNull { visualColumn(it, it.length) } ?: 0)
        private set

    /** The hints drawn in lines between their characters, by line (see [LineInlays]); none by default. */
    var inlays by mutableStateOf<Map<Int, LineInlays>>(emptyMap())

    /** Folded regions; its regions are recomputed by [EditorState] after edits. */
    val folding = FoldingModel(this)

    /** Precomputed block-comment state per line, see [org.example.kotlineditor.syntax.SyntaxHighlighter]. */
    var commentStates by mutableStateOf<BooleanArray?>(null)

    /** Brackets open where each line starts, for their colors; see [org.example.kotlineditor.syntax.LineStarts]. */
    var bracketDepths by mutableStateOf<IntArray?>(null)

    /** Tree-sitter highlighting, when the language has a grammar; the line lexer colors the rest. */
    var syntax: SyntaxSession? = null
        set

    /**
     * Told of every change of the text (typing, undo, reload...), with the range it replaced in the text before:
     * the language servers' copy of the document follows it (see [DocumentSync]).
     */
    var editListener: ((TextChange) -> Unit)? = null

    /** Told of every change too, after [editListener]: the diagnostics and usage hints shown follow the text. */
    var markerListener: ((TextChange) -> Unit)? = null

    /** Selections before each Ctrl+W, for Ctrl+Shift+W. */
    val selectionHistory = ArrayDeque<Pair<TextPos?, TextPos>>()

    val isModified: Boolean get() = version != savedVersion

    val hasSelection: Boolean get() = anchor != null && anchor != caret

    val lineCount: Int get() = lines.size

    // Column the caret tries to keep when moving vertically.
    private var preferredVisualColumn = 0
    private val undoStack = ArrayDeque<UndoStep>()
    private val redoStack = ArrayDeque<UndoStep>()

    // The edits of one action run at every caret, while it runs; null otherwise.
    private var group: MutableList<Edit>? = null
    private var groupBefore: List<Caret> = emptyList()

    private sealed interface UndoStep

    // An action run at several carets: undone and redone at once, restoring every caret.
    private class EditGroup(val edits: List<Edit>, val before: List<Caret>, val after: List<Caret>) : UndoStep {
        // Typing at several carets is one undo step too, when each caret typed after its own previous edit.
        fun absorbs(next: EditGroup): Boolean {
            val sameCarets = next.edits.size == edits.size
            return sameCarets && edits.indices.all { edits[it].absorbs(next.edits[it]) }
        }

        fun merge(next: EditGroup): EditGroup {
            val merged = edits.indices.map { edits[it].merge(next.edits[it]) }
            return EditGroup(merged, before, next.after)
        }
    }

    private class Edit(
        val start: TextPos,
        val removed: String,
        val inserted: String,
        val caretBefore: TextPos,
        val anchorBefore: TextPos?,
        val caretAfter: TextPos,
    ) : UndoStep {
        // Consecutive typing of the same kind of characters is one undo step, like IntelliJ.
        fun absorbs(at: TextPos, removedNow: String, typed: String): Boolean {
            val oneChar = removedNow.isEmpty() && typed.length == 1 && typed != "\n"
            val wasTyping = removed.isEmpty() && inserted.isNotEmpty() && '\n' !in inserted
            val sameKind = inserted.lastOrNull()?.isWordChar() == typed.firstOrNull()?.isWordChar()
            val continues = sameKind && endOf(start, inserted) == at
            return oneChar && wasTyping && continues
        }

        fun absorbs(next: Edit): Boolean = absorbs(next.start, next.removed, next.inserted)

        fun merge(next: Edit): Edit =
            Edit(start, "", inserted + next.inserted, caretBefore, anchorBefore, next.caretAfter)
    }

    /** Every caret, the primary one first. */
    fun carets(): List<Caret> = listOf(Caret(caret, anchor, preferredVisualColumn)) + extraCarets

    /**
     * Sets every caret, [all] holding the primary one first; carets at the same place or whose selections overlap
     * become one.
     */
    fun setCarets(all: List<Caret>) {
        val merged = mergeCarets(all.map { it.clamped(this) })
        val primary = merged.firstOrNull() ?: return
        anchor = primary.anchor
        caret = primary.pos
        preferredVisualColumn = primary.preferredColumn.takeIf { it >= 0 } ?: visualColumnOf(caret.line, caret.column)
        extraCarets = merged.drop(1)
    }

    /** Groups the edits made by [block] into one undo step, restoring every caret when undone (see [forEachCaret]). */
    internal fun editGroup(block: () -> Unit) {
        val outer = group
        if (outer != null) return block()
        val edits = mutableListOf<Edit>()
        group = edits
        groupBefore = carets()
        try {
            block()
        } finally {
            group = null
        }
        if (edits.isNotEmpty()) pushGroup(EditGroup(edits, groupBefore, carets()))
    }

    private fun pushGroup(step: EditGroup) {
        val last = undoStack.lastOrNull()
        if (last is EditGroup && last.absorbs(step)) {
            undoStack[undoStack.lastIndex] = last.merge(step)
        } else {
            undoStack.addLast(step)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
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
        extraCarets = emptyList()
        anchor = if (extendSelection) anchor ?: caret else null
        caret = target
        preferredVisualColumn = visualColumnOf(target.line, target.column)
    }

    /** Moves to [targetLine] keeping the column the caret had before vertical moves started. */
    fun moveVerticallyTo(targetLine: Int, extend: Boolean = false) {
        val line = targetLine.coerceIn(0, lines.lastIndex)
        val keep = preferredVisualColumn
        moveTo(TextPos(line, columnOfVisual(line, keep)), extend)
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
        preferredVisualColumn = visualColumnOf(after.line, after.column)
        redoStack.clear()
        folding.onEdit()
        selectionHistory.clear()
        val edit = Edit(s, removed, text, caretBefore, anchorBefore, after)
        val grouped = group
        val last = undoStack.lastOrNull()
        if (grouped != null) {
            grouped += edit
        } else if (last is Edit && last.absorbs(edit)) {
            undoStack[undoStack.lastIndex] = last.merge(edit)
        } else {
            undoStack.addLast(edit)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        version++
    }

    fun undo(): Boolean {
        val step = undoStack.removeLastOrNull()
        when (step) {
            is Edit -> {
                applyRaw(step.start, endOf(step.start, step.inserted), step.removed)
                anchor = step.anchorBefore
                caret = step.caretBefore
            }

            is EditGroup -> {
                // Every caret is restored after: none follows the edits meanwhile.
                extraCarets = emptyList()
                step.edits.asReversed().forEach { applyRaw(it.start, endOf(it.start, it.inserted), it.removed) }
                setCarets(step.before)
            }

            null -> {
                return false
            }
        }
        redoStack.addLast(step)
        folding.onEdit()
        version++
        return true
    }

    fun redo(): Boolean {
        val step = redoStack.removeLastOrNull()
        when (step) {
            is Edit -> {
                applyRaw(step.start, endOf(step.start, step.removed), step.inserted)
                anchor = null
                caret = step.caretAfter
            }

            is EditGroup -> {
                extraCarets = emptyList()
                step.edits.forEach { applyRaw(it.start, endOf(it.start, it.removed), it.inserted) }
                setCarets(step.after)
            }

            null -> {
                return false
            }
        }
        undoStack.addLast(step)
        folding.onEdit()
        version++
        return true
    }

    private fun applyRaw(start: TextPos, end: TextPos, text: String): TextPos {
        val change = TextChange(start.line, start.column, end.line, end.column, text)
        editListener?.invoke(change)
        markerListener?.invoke(change)
        caretTracker?.invoke(change)
        if (extraCarets.isNotEmpty()) extraCarets = extraCarets.map { it.mappedThrough(change) }
        val prefix = lines[start.line].substring(0, start.column)
        val suffix = lines[end.line].substring(end.column)
        val parts = text.split('\n')
        val replacement =
            parts.mapIndexed { index, part ->
                val head = if (index == 0) prefix else ""
                val tail = if (index == parts.lastIndex) suffix else ""
                head + part + tail
            }
        val removedLines = end.line - start.line + 1
        if (start.line == end.line && replacement.size == 1) {
            lines[start.line] = replacement[0]
        } else if (removedLines > BULK_REMOVAL_LINES) {
            lines.rebuild(start.line, end.line, replacement)
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

        // Above this many lines removed by one edit, [rebuild] is cheaper than removing them from the list.
        const val BULK_REMOVAL_LINES = 64
    }
}

// The snapshot list removes a range one line at a time, each removal moving every line after it: replacing the whole
// of a 200,000-line file (Ctrl+A, then a key) took 15 seconds. Lines [firstLine]..[lastLine] are replaced by
// [replacement] by building the list again instead, at once.
private fun SnapshotStateList<String>.rebuild(firstLine: Int, lastLine: Int, replacement: List<String>) {
    val kept = size - (lastLine - firstLine + 1)
    val rebuilt = ArrayList<String>(kept + replacement.size)
    rebuilt.addAll(subList(0, firstLine))
    rebuilt.addAll(replacement)
    rebuilt.addAll(subList(lastLine + 1, size))
    clear()
    addAll(rebuilt)
}

// Position right after `text` inserted at `start`.
internal fun endOf(start: TextPos, text: String): TextPos {
    val newlines = text.count { it == '\n' }
    return if (newlines == 0) {
        TextPos(start.line, start.column + text.length)
    } else {
        TextPos(start.line + newlines, text.length - text.lastIndexOf('\n') - 1)
    }
}
