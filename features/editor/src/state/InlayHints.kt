package org.example.kotlineditor.state

import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.InlayHintKind
import org.example.kotlineditor.model.SemanticToken
import org.example.kotlineditor.model.TextChange

/** [hint] after [change]: it follows its character; null when the change replaced the text around it. */
internal fun shiftedHint(hint: InlayHint, change: TextChange): InlayHint? {
    val pos = TextPos(hint.line, hint.column)
    val changeStart = TextPos(change.startLine, change.startColumn)
    val changeEnd = TextPos(change.endLine, change.endColumn)
    val replaced = changeStart < pos && pos < changeEnd
    if (replaced) return null
    val moved = shifted(pos, change)
    return hint.copy(line = moved.line, column = moved.column)
}

/**
 * The hints worth showing, like IntelliJ: not the type hints right after a closing brace, which name the block it
 * closes (phpactor's `class User` after a class, `method name` after a method).
 */
internal fun shownHints(hints: List<InlayHint>, lines: List<String>): List<InlayHint> =
    hints.filterNot { closesBlock(it, lines) }

private fun closesBlock(hint: InlayHint, lines: List<String>): Boolean {
    val before = lines.getOrNull(hint.line)?.getOrNull(hint.column - 1)
    val isType = hint.kind == InlayHintKind.Type
    return isType && before == '}'
}

/** The hints of each line, to draw in the code. */
fun inlaysOf(hints: List<InlayHint>): Map<Int, LineInlays> =
    hints
        .groupBy { it.line }
        .mapValues { (_, onLine) ->
            val sorted = onLine.sortedBy { it.column }
            LineInlays(IntArray(sorted.size) { sorted[it].column }, sorted.map { it.label })
        }

/** [token] after [change]: it follows its word; null when the change touched the word (until the server answers). */
internal fun shiftedToken(token: SemanticToken, change: TextChange): SemanticToken? {
    val start = TextPos(token.line, token.column)
    val end = TextPos(token.line, token.column + token.length)
    val changeStart = TextPos(change.startLine, change.startColumn)
    val changeEnd = TextPos(change.endLine, change.endColumn)
    // Typed or deleted in the word, or right after it (the word grows): its kind is not known anymore.
    val touched = changeStart <= end && changeEnd >= start
    if (touched) return null
    val moved = shifted(start, change)
    return token.copy(line = moved.line, column = moved.column)
}
