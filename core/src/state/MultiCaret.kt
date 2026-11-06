package org.example.kotlineditor.state

import org.example.kotlineditor.model.TextChange

// Multiple carets (IntelliJ keymap): Alt+click, Alt+J (see `Occurrences.kt`) and column selection (Alt+Shift+drag).

/**
 * Runs [action] at every caret, as if each one were the only one, top to bottom: the carets after the one acting
 * follow its edits. The edits of every caret are one undo step. [action] receives the index of the caret in document
 * order. Without other carets, it runs once at the caret.
 */
fun Document.forEachCaret(action: Document.(Int) -> Unit) {
    if (extraCarets.isEmpty()) return action(0)
    // The carets in document order, with their place in the list of carets.
    val ordered = carets().withIndex().sortedBy { it.value.start }
    val done = ArrayList<Caret>(ordered.size)
    val shift = EditShift()
    editGroup {
        caretTracker = { change ->
            shift.record(change)
            followEdit(done, change)
        }
        try {
            for ((i, entry) in ordered.withIndex()) {
                setCarets(listOf(shift.apply(entry.value)))
                action(i)
                done += carets().first()
            }
        } finally {
            caretTracker = null
        }
        val places = ordered.map { it.index }
        setCarets(done.indices.sortedBy { places[it] }.map { done[it] })
    }
}

// The carets already done are before the edit of the next ones, but an edit can reach back over the last of them
// (Ctrl+Backspace): those follow it, the others stay.
private fun followEdit(done: MutableList<Caret>, change: TextChange) {
    val start = TextPos(change.startLine, change.startColumn)
    var i = done.lastIndex
    while (i >= 0 && done[i].end > start) {
        done[i] = done[i].mappedThrough(change)
        i--
    }
}

/** The number of carets. */
val Document.caretCount: Int get() = 1 + extraCarets.size

/** Escape: keeps the primary caret only (and its selection). */
fun Document.removeExtraCarets() = setCarets(listOf(carets().first()))

/**
 * Alt+click: adds a caret at [pos], which becomes the primary one, or removes the caret already there when there are
 * others.
 */
fun Document.toggleCaretAt(pos: TextPos) {
    val target = clamp(pos)
    val all = carets()
    val existing = all.firstOrNull { it.pos == target && !it.hasSelection }
    if (existing != null && all.size > 1) {
        setCarets(all.filter { it !== existing })
    } else {
        setCarets(listOf(Caret(target)) + all)
    }
}

/**
 * Column selection (Alt+Shift+drag) from screen cell [fromVisual] of [fromLine] to cell [toVisual] of [toLine]: one
 * caret per line, selecting the cells between both columns (a line too short gets a caret at its end), the primary
 * caret on [toLine].
 */
fun Document.selectColumns(fromLine: Int, fromVisual: Int, toLine: Int, toVisual: Int) {
    val first = minOf(fromLine, toLine).coerceIn(0, lines.lastIndex)
    val last = maxOf(fromLine, toLine).coerceIn(0, lines.lastIndex)
    val carets =
        (first..last).filterNot(folding::isHidden).map { line ->
            val anchor = TextPos(line, columnOfVisual(line, fromVisual))
            val pos = TextPos(line, columnOfVisual(line, toVisual))
            Caret(pos, anchor.takeIf { it != pos }, toVisual)
        }
    val primary = carets.firstOrNull { it.pos.line == toLine } ?: carets.lastOrNull() ?: return
    setCarets(listOf(primary) + carets.filter { it !== primary })
}

/**
 * What Ctrl+C copies with several carets: the selection of each caret (its line when it has none), one per line, in
 * document order.
 */
fun Document.selectedTextOfCarets(): String {
    val ordered = carets().sortedBy { it.start }
    return ordered.joinToString("\n") { caret ->
        if (caret.hasSelection) textBetween(caret.start, caret.end) else lines[caret.pos.line]
    }
}

/**
 * Ctrl+V: [text] at every caret; with as many carets as lines of [text], each caret gets its own line (what was
 * copied from as many carets).
 */
fun Document.paste(text: String) {
    val parts = text.removeSuffix("\n").split('\n')
    val spread = extraCarets.isNotEmpty() && parts.size == caretCount
    forEachCaret { index -> insert(if (spread) parts[index] else text) }
}
