package org.example.kotlineditor.lsp.intelligence

import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.TextRange

private val OVERLOAD_NOTE = Regex("""\s*\(\+\d+ overloads?\)""")
private const val CONSTRUCTOR_PREFIX = "new "
private const val FENCE = "```"
private const val LANGUAGE = "typescript"
private const val CALL_OPENING = '('

/**
 * TypeScript hovers a function or a constructor with several signatures as `new () => Date (+3 overloads)`: only
 * one signature, and the count of the others. The hover then lists them all, which signature help knows when asked
 * just after the opening parenthesis of the call.
 */
internal object HoverOverloads {
    /** Whether [markdown] only counts the other signatures of what it describes. */
    fun hidesSome(markdown: String?): Boolean = markdown != null && OVERLOAD_NOTE.containsMatchIn(markdown)

    /** The position just inside the call the hovered name starts, null when it is not followed by a `(`. */
    fun insideCall(document: MirroredDocument, at: DocumentPosition, range: TextRange?): DocumentPosition? {
        val line = range?.endLine ?: at.line
        val column = range?.endColumn ?: at.column
        val next = document.line(line).getOrNull(column)
        return if (next == CALL_OPENING) at.copy(line = line, column = column + 1) else null
    }

    /** [markdown] without the count, followed by the [signatures] it counted. */
    fun listing(markdown: String, signatures: List<String>): String {
        val withoutCount = markdown.replace(OVERLOAD_NOTE, "")
        if (signatures.isEmpty()) return withoutCount
        val constructor = markdown.lineSequence().any { it.startsWith(CONSTRUCTOR_PREFIX) }
        val prefix = if (constructor) CONSTRUCTOR_PREFIX else ""
        val lines = signatures.joinToString("\n") { "$prefix$it" }
        return listOf(withoutCount, "", "$FENCE$LANGUAGE", lines, FENCE).joinToString("\n")
    }
}
