package org.example.kotlineditor.state

// Alt+J and its variants: carets on the occurrences of a text (IntelliJ keymap).

/**
 * Alt+J: without a selection, selects the word at the caret; with one, adds a caret selecting the next occurrence of
 * the selected text after the last caret, wrapping at the end of the document. Returns false when there is no other
 * occurrence.
 */
fun Document.selectNextOccurrence(): Boolean = if (hasSelection) addNextOccurrence() else selectWordForOccurrences()

// The word at the caret, whose next occurrences Alt+J selects as whole words.
private fun Document.selectWordForOccurrences(): Boolean {
    val word = identifierAt(caret)
    if (word != null) {
        selectWordAt(caret)
        occurrenceWord = word
    }
    return word != null
}

private fun Document.addNextOccurrence(): Boolean {
    val all = carets()
    val text = selectedText()
    val wholeWord = text == occurrenceWord
    val next = nextOccurrence(all, text, wholeWord)
    if (next != null) {
        setCarets(listOf(Caret(endOf(next, text), next)) + all)
        occurrenceWord = text.takeIf { wholeWord }
    }
    return next != null
}

// The first occurrence of [text] no caret selects, after every caret or else from the start.
private fun Document.nextOccurrence(all: List<Caret>, text: String, wholeWord: Boolean): TextPos? {
    val after = all.maxOf { it.end }
    val taken = all.map { it.start }.toSet()
    val free = occurrencesOf(text, wholeWord).filterNot { it in taken }
    return free.firstOrNull { it >= after } ?: free.firstOrNull()
}

/** Shift+Alt+J: removes the caret Alt+J added last (the primary one), the one added before becoming primary. */
fun Document.unselectOccurrence() {
    val others = extraCarets
    if (others.isEmpty()) return clearSelection()
    val keep = occurrenceWord
    setCarets(others)
    occurrenceWord = keep
}

/** Ctrl+Alt+Shift+J: a caret on every occurrence of the selected text (or of the word at the caret). */
fun Document.selectAllOccurrences(): Boolean {
    val word = if (hasSelection) null else identifierAt(caret)
    val text = word ?: selectedText()
    val wholeWord = word != null || text == occurrenceWord
    val carets = occurrencesOf(text, wholeWord).map { Caret(endOf(it, text), it) }
    val position = if (hasSelection) carets().first().start else caret
    val around = carets.firstOrNull { it.start <= position && position <= it.end }
    val primary = around ?: carets.firstOrNull()
    if (primary != null) {
        setCarets(listOf(primary) + carets.filter { it !== primary })
        occurrenceWord = text.takeIf { wholeWord }
    }
    return primary != null
}

// Start of every occurrence of [text] in the document, in order; whole words only when [wholeWord].
private fun Document.occurrencesOf(text: String, wholeWord: Boolean): List<TextPos> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    val result = ArrayList<TextPos>()
    for (index in lines.indices) {
        val line = lines[index]
        var from = line.indexOf(parts[0])
        while (from >= 0) {
            val start = TextPos(index, from)
            if (matchesAt(start, parts, wholeWord)) result += start
            from = line.indexOf(parts[0], from + 1)
        }
    }
    return result
}

private fun Document.matchesAt(start: TextPos, parts: List<String>, wholeWord: Boolean): Boolean {
    val lastLine = start.line + parts.lastIndex
    if (lastLine > lines.lastIndex) return false
    val firstLine = lines[start.line]
    val matches =
        if (parts.size == 1) {
            true
        } else {
            val headEnds = firstLine.length == start.column + parts[0].length
            val middle = (1 until parts.lastIndex).all { lines[start.line + it] == parts[it] }
            headEnds && middle && lines[lastLine].startsWith(parts.last())
        }
    val end = endOf(start, parts.joinToString("\n"))
    return matches && (!wholeWord || isWholeWord(start, end))
}

private fun Document.isWholeWord(start: TextPos, end: TextPos): Boolean {
    val before = lines[start.line].getOrNull(start.column - 1)
    val after = lines[end.line].getOrNull(end.column)
    val boundedBefore = before == null || !before.isWordChar()
    val boundedAfter = after == null || !after.isWordChar()
    return boundedBefore && boundedAfter
}
