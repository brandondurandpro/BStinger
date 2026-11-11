package org.example.kotlineditor.acp

import org.example.kotlineditor.model.AgentDiff

// What the transcript keeps of the content of a tool call: what its card shows, never all the agent sent. A file read,
// the output of a command or a file written weigh up to megabytes, and a transcript is held in memory by the server
// and by every IDE that follows it, for as long as its conversation lasts: whole, a few long conversations took
// hundreds of megabytes on both sides.

/** Characters kept of the output of a tool call: its beginning, more than the few lines a card shows. */
internal const val MAX_TOOL_OUTPUT = 4_000

/** Characters kept of a thought of the agent: its beginning, of which the conversation shows a few lines. */
internal const val MAX_THOUGHT = 4_000

/** Lines kept of what a change removes, and of what it adds. */
internal const val MAX_DIFF_LINES = 40

/** Characters kept of a line of a change. */
internal const val MAX_DIFF_LINE = 1_000

/** The beginning of [texts], the blocks of text of a tool call, one after the other. */
internal fun shownOutput(texts: List<String>): String {
    val shown = StringBuilder()
    for (text in texts.filter { it.isNotEmpty() }) {
        val separator = if (shown.isEmpty()) "" else "\n"
        val room = MAX_TOOL_OUTPUT - shown.length - separator.length
        if (room <= 0) break
        shown.append(separator).append(beginningOf(text, room))
    }
    return shown.toString()
}

/** The thought [text] with [chunk] after it, as the transcript keeps it: its beginning. */
internal fun shownThought(text: String, chunk: String): String = beginningOf(text + chunk, MAX_THOUGHT)

/**
 * The change of the file at [path] from [oldText] (null for a new file) to [newText], as the transcript keeps it: the
 * lines that differ between the first and last lines both texts share, the first [MAX_DIFF_LINES] of each side.
 */
internal fun shownDiff(path: String, oldText: String?, newText: String): AgentDiff {
    val old = oldText?.lines().orEmpty()
    val new = newText.lines()
    val common = minOf(old.size, new.size)
    val prefix = (0 until common).firstOrNull { old[it] != new[it] } ?: common
    val maxSuffix = common - prefix
    val suffix = (0 until maxSuffix).firstOrNull { old[old.size - 1 - it] != new[new.size - 1 - it] } ?: maxSuffix
    val removed = old.subList(prefix, old.size - suffix)
    val added = new.subList(prefix, new.size - suffix)
    val keptRemoved = removed.take(MAX_DIFF_LINES).map { beginningOf(it, MAX_DIFF_LINE) }
    val keptAdded = added.take(MAX_DIFF_LINES).map { beginningOf(it, MAX_DIFF_LINE) }
    val hidden = removed.size - keptRemoved.size + added.size - keptAdded.size
    return AgentDiff(path, keptRemoved, keptAdded, hidden)
}

// The first [length] characters of [text], as a string of its own (the whole text is no longer held), never ending
// in the middle of a character made of two.
private fun beginningOf(text: String, length: Int): String {
    if (text.length <= length) return text
    val cutsPair = length > 0 && Character.isHighSurrogate(text[length - 1])
    val end = if (cutsPair) length - 1 else length
    return text.substring(0, end)
}
