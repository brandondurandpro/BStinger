package org.example.kotlineditor.state

// Closing the tag just opened in an HTML or Vue file: typing `>` after `<Navbar class="x"` adds `</Navbar>`.

private const val ATTRIBUTE = """[\w:@.#\[\]()$-]+(?:=(?:"[^"]*"|'[^']*'|[^\s"'<>=`]+))?"""
private val OPEN_TAG = Regex("""<([A-Za-z][\w.:-]*)(?:\s+$ATTRIBUTE)*\s*$""")
private val VOID_ELEMENTS =
    setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val RAW_TEXT_BLOCKS = listOf("script", "style")
private val TAG_EXTENSIONS = setOf("vue", "html", "htm")

/** Whether [path] is a file whose tags are closed as they are typed. */
fun closesTags(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in TAG_EXTENSIONS

/**
 * The closing tag to add when `>` is typed at the caret (`</Navbar>`), null when the text before the caret is not
 * an opening tag: a self-closed or void element, a closing tag, an arrow or a comparison, a script or style body.
 */
fun Document.closingTagForTypedEnd(): String? {
    val line = lines[caret.line]
    val before = line.substring(0, minOf(caret.column, line.length))
    val after = line.substring(minOf(caret.column, line.length))
    val tag = OPEN_TAG.find(before)
    val name = tag?.groupValues?.get(1)
    val closing = name?.let { "</$it>" }
    val selfClosed = before.trimEnd().endsWith("/")
    val void = name != null && name.lowercase() in VOID_ELEMENTS
    val alreadyClosed = closing != null && after.startsWith(closing)
    val inRawText = tag != null && isInRawText(TextPos(caret.line, tag.range.first))
    val notOpening = selfClosed || void
    val skipped = notOpening || alreadyClosed || inRawText
    return if (closing == null || skipped) null else closing
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
