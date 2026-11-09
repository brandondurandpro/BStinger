package org.example.kotlineditor.state

// Closing the tag just opened in an HTML or Vue file: typing `>` after `<Navbar class="x"` adds `</Navbar>`.

private const val ATTRIBUTE = """[\w:@.#\[\]()$-]+(?:=(?:"[^"]*"|'[^']*'|[^\s"'<>=`]+))?"""
private val OPEN_TAG = Regex("""<([A-Za-z][\w.:-]*)(?:\s+$ATTRIBUTE)*\s*$""")
private val VOID_ELEMENTS =
    setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val RAW_TEXT_BLOCKS = listOf("script", "style")

// A tag written over more lines than this is not closed automatically.
private const val MAX_TAG_LINES = 30
private val TAG_EXTENSIONS = setOf("vue", "html", "htm")

/** Whether [path] is a file whose tags are closed as they are typed. */
fun closesTags(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in TAG_EXTENSIONS

/**
 * The closing tag to add when `>` is typed at the caret (`</Navbar>`), null when the text before the caret is not
 * an opening tag: a self-closed or void element, a closing tag, an arrow or a comparison, a script or style body.
 */
fun Document.closingTagForTypedEnd(): String? {
    val line = lines[caret.line]
    val column = minOf(caret.column, line.length)
    val after = line.substring(column)
    val firstLine = maxOf(0, caret.line - MAX_TAG_LINES)
    val previous = lines.subList(firstLine, caret.line)
    val before = (previous + line.substring(0, column)).joinToString("\n")
    val tag = OPEN_TAG.find(before)
    val name = tag?.groupValues?.get(1)
    val closing = name?.let { "</$it>" }
    val selfClosed = before.trimEnd().endsWith("/")
    val void = name != null && name.lowercase() in VOID_ELEMENTS
    val alreadyClosed = closing != null && after.startsWith(closing)
    val inRawText = tag != null && isInRawText(positionIn(before, tag.range.first, firstLine))
    val notOpening = selfClosed || void
    val skipped = notOpening || alreadyClosed || inRawText
    return if (closing == null || skipped) null else closing
}

// The position of [offset] in [text], which starts at the line [firstLine].
private fun positionIn(text: String, offset: Int, firstLine: Int): TextPos {
    val head = text.substring(0, offset)
    val lineBreaks = head.count { it == '\n' }
    return TextPos(firstLine + lineBreaks, offset - (head.lastIndexOf('\n') + 1))
}

// Whether [pos] is inside the body of a `<script>` or `<style>` block: what is written there is not markup.
private fun Document.isInRawText(pos: TextPos): Boolean {
    val text = textBetween(TextPos(0, 0), pos)
    return RAW_TEXT_BLOCKS.any { block ->
        val opened = text.lastIndexOf("<$block", ignoreCase = true)
        val closed = text.lastIndexOf("</$block", ignoreCase = true)
        opened > closed
    }
}
