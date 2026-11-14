package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileVersion

// Typing and editing actions (IntelliJ keymap).

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
private val CLOSERS = setOf(')', ']', '}')
private val QUOTES = setOf('"', '\'')
private val FREE_AFTER = CLOSERS + setOf(',', ';')
private val OPENERS = setOf('{', '(', '[')

/** Types [text] at the caret, replacing the selection. */
fun Document.insert(text: String) {
    val (start, end) = selectionRange() ?: Pair(caret, caret)
    replace(start, end, text)
}

/**
 * Types one character like IntelliJ does: closes brackets and quotes, steps over an auto-inserted closer,
 * and wraps the selection when an opening bracket or quote is typed over it. With [closeTags], `>` that ends an
 * opening tag adds its closing tag after the caret.
 */
fun Document.typeChar(c: Char, closeTags: Boolean = false) {
    val endsTag = c == '>' && closeTags && !hasSelection
    if (endsTag && closeTypedTag()) return
    typeCharacter(c)
}

// `>` that ends an opening tag: its closing tag is added after the caret.
private fun Document.closeTypedTag(): Boolean {
    val closingTag = closingTagForTypedEnd() ?: return false
    insert(">$closingTag")
    moveTo(TextPos(caret.line, caret.column - closingTag.length))
    return true
}

private fun Document.typeCharacter(c: Char) {
    val range = selectionRange()
    val closer = PAIRS[c]
    val next = lines[caret.line].getOrNull(caret.column)
    val stepsOver = next == c && (c in CLOSERS || c in QUOTES)
    when {
        range != null && closer != null -> {
            replace(range.first, range.second, "$c${textBetween(range.first, range.second)}$closer")
            select(TextPos(range.first.line, range.first.column + 1), TextPos(caret.line, caret.column - 1))
        }

        range == null && stepsOver -> {
            moveRight()
        }

        range == null && closer != null && autoCloses(c, next) -> {
            insert("$c$closer")
            moveTo(TextPos(caret.line, caret.column - 1))
        }

        else -> {
            insert(c.toString())
        }
    }
}

// A pair is closed only before a blank, a closer or a separator, and a quote is not paired after a letter (don't).
private fun Document.autoCloses(c: Char, next: Char?): Boolean {
    val freeAfter = next == null || next.isWhitespace() || next in FREE_AFTER
    val afterLetter = lines[caret.line].getOrNull(caret.column - 1)?.isLetterOrDigit() == true
    return freeAfter && !(c in QUOTES && afterLetter)
}

/**
 * Enter: new line keeping the current indentation, plus one level after an opening bracket. Between an opening
 * bracket and its closer (`{|}`), the block opens: the caret lands on an indented line and the closer moves to a
 * line of its own, at the original indentation.
 */
fun Document.newLine() {
    val line = lines[caret.line]
    val indent = line.takeWhile { it == ' ' || it == '\t' }
    val before = line.substring(0, minOf(caret.column, line.length)).trimEnd()
    val opener = before.lastOrNull()
    val extra = if (opener in OPENERS) " ".repeat(TAB_SIZE) else ""
    val closer = opener?.let { PAIRS[it] }
    val nextChar = line.getOrNull(caret.column)
    val closerFollows = closer != null && nextChar == closer
    val splitsEmptyPair = closerFollows && !hasSelection
    if (splitsEmptyPair) {
        val closerLine = "\n" + indent
        insert("\n" + indent + extra + closerLine)
        moveTo(TextPos(caret.line - 1, indent.length + extra.length))
    } else {
        insert("\n" + indent + extra)
    }
}

/** Backspace; deleting the opener of an empty pair `()` removes both characters, like IntelliJ. */
fun Document.backspace(word: Boolean = false) {
    val (l, c) = caret
    val line = lines[l]
    val closer = line.getOrNull(c - 1)?.let { PAIRS[it] }
    val emptyPair = !word && closer != null && line.getOrNull(c) == closer
    when {
        hasSelection -> {
            insert("")
        }

        emptyPair -> {
            replace(TextPos(l, c - 1), TextPos(l, c + 1), "")
        }

        // At the very start of the document there is nothing to delete.
        caret != TextPos(0, 0) -> {
            val end = caret
            moveLeft(word = word)
            replace(caret, end, "")
        }
    }
}

fun Document.delete(word: Boolean = false) {
    if (hasSelection) return insert("")
    val start = caret
    moveRight(word = word)
    val end = caret
    moveTo(start)
    if (end != start) replace(start, end, "")
}

/** Ctrl+D: duplicates the selection or the current line. */
fun Document.duplicate() {
    val range = selectionRange()
    if (range != null) {
        replace(range.second, range.second, textBetween(range.first, range.second))
        select(range.second, caret)
    } else {
        val (l, column) = caret
        replace(TextPos(l, lines[l].length), TextPos(l, lines[l].length), "\n" + lines[l])
        moveTo(TextPos(l + 1, column))
    }
}

/** Ctrl+Y (IntelliJ keymap): deletes the lines touched by the caret or the selection. */
fun Document.deleteLines() {
    val (first, last) = selectedLineRange()
    val isLast = last == lines.lastIndex
    val start = if (isLast && first > 0) TextPos(first - 1, lines[first - 1].length) else TextPos(first, 0)
    val end = if (isLast) TextPos(last, lines[last].length) else TextPos(last + 1, 0)
    replace(start, end, "")
    moveTo(TextPos(minOf(first, lines.lastIndex), 0))
}

/**
 * Replaces the whole content (file changed on disk) as one undoable edit, keeping the caret in place; [diskVersion]
 * is what the disk answered when the new content was read ([FileVersion]), `null` when it told nothing: the
 * version is only updated when it is known, so a save is not checked against a version that went stale.
 */
fun Document.reload(newLines: List<String>, diskVersion: FileVersion? = null) {
    val keep = caret
    replace(TextPos(0, 0), TextPos(lines.lastIndex, lines.last().length), newLines.joinToString("\n"))
    moveTo(keep)
    markSaved()
    if (diskVersion != null) this.diskVersion = diskVersion
    diskConflict = false
}
