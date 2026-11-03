package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.TextChange

// Formatting while typing (textDocument/onTypeFormatting): after `;` or `}`, the language server formats around.

/** The trigger characters of the server of the document, asked once per server process. */
internal suspend fun CodeInsightState.formatTriggersFromServer(insight: DocumentInsight) {
    if (insight.typeFormattingTriggers != null) return
    val document = insight.document
    val result = askVersion(insight.path, document) { servers, at -> servers.typeFormattingTriggers(insight.path, at) }
    val status = result?.status
    if (status == FeatureStatus.Ready) insight.typeFormattingTriggers = result.characters.toSet()
    if (status == FeatureStatus.Unsupported) insight.typeFormattingTriggers = emptySet()
}

/**
 * [change] typed a trigger character (or Enter, with its indentation): the server's edits around it are applied,
 * unless typing went on meanwhile. An undo or a longer paste is no typing.
 */
internal fun CodeInsightState.formatAfterTyping(insight: DocumentInsight, change: TextChange) {
    val typed = typedCharacter(change)
    val triggers = insight.typeFormattingTriggers.orEmpty()
    if (typed == null || typed !in triggers) return
    val after =
        if (typed == "\n") {
            TextPos(change.startLine + 1, change.text.length - 1)
        } else {
            TextPos(change.startLine, change.startColumn + 1)
        }
    val document = insight.document
    scope.launch {
        val version = document.version
        val result =
            askAt(
                insight.path,
                document,
                after,
            ) { servers, at -> servers.formatOnType(at, typed, FormatOptions()) }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready && it.message == null }
        val unchanged = document.version == version
        val edits = ready?.takeIf { it.changes.isNotEmpty() }
        if (edits != null && unchanged) host.apply(edits)
    }
}

// The character an insertion typed: one character, or a line break followed by its indentation.
private fun typedCharacter(change: TextChange): String? {
    val insertion = change.startLine == change.endLine && change.startColumn == change.endColumn
    val text = change.text
    val enter = text.startsWith("\n") && text.drop(1).isBlank() && '\n' !in text.drop(1)
    return when {
        !insertion -> null
        text.length == 1 -> text
        enter -> "\n"
        else -> null
    }
}
