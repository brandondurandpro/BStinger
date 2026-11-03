package org.example.kotlineditor.state

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.TextRange

// Ctrl+W in a file with a language server: the ranges of textDocument/selectionRange, like IntelliJ's syntax-aware
// selection; the editor's own steps (word, line, block, document) when the server has none.

private const val SELECTION_TIMEOUT_MS = 1_500L

/** The ranges the server gave around a position of one version of a document, and the presses waiting for them. */
internal class SelectionSteps(val document: Document, val version: Int, val job: Job? = null) {
    /** Innermost first; null while asked. */
    var ranges: List<TextRange>? = null

    /** Ctrl+W presses made while the server was asked. */
    var pending = 1
}

/** Ctrl+W: grows the selection to the next range of the language server around it, else to the editor's own. */
fun CodeInsightState.extendSelection(path: String, document: Document) {
    val steps = selectionSteps?.takeIf { it.document === document && it.version == document.version }
    val ranges = steps?.ranges
    when {
        ranges != null && stillAbout(ranges, selectionOrCaret(document)) -> growTo(document, ranges)
        steps != null && ranges == null -> steps.pending++
        else -> askSelectionRanges(path, document)
    }
}

private fun CodeInsightState.askSelectionRanges(path: String, document: Document) {
    val version = document.version
    val from = selectionOrCaret(document).start
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
            // Out of reach, the editor's own steps answer at once rather than a selection that waits.
            val result =
                withTimeoutOrNull(SELECTION_TIMEOUT_MS) {
                    askAt(path, document, from) { servers, at -> servers.selectionRanges(at) }
                }
            val steps = selectionSteps?.takeIf { it.document === document && it.version == version }
            val ranges = result?.ranges?.takeIf { result.status == FeatureStatus.Ready }.orEmpty()
            if (steps != null && document.version == version) {
                steps.ranges = ranges
                repeat(steps.pending) { growTo(document, ranges) }
                steps.pending = 0
                // A server that could not answer is asked again at the next press.
                val answered = result != null && result.status != FeatureStatus.NotReady
                if (!answered) selectionSteps = null
            }
        }
    selectionSteps = SelectionSteps(document, version, job)
    job.start()
}

// One step: the smallest range of the server holding the selection and larger than it, else the editor's own.
private fun growTo(document: Document, ranges: List<TextRange>) {
    val current = selectionOrCaret(document)
    val next = ranges.firstOrNull { current in it && current != Span.from(it) }
    if (next == null) {
        document.extendSelection()
        return
    }
    document.selectionHistory.addLast(document.anchor to document.caret)
    document.select(TextPos(next.startLine, next.startColumn), TextPos(next.endLine, next.endColumn))
}

// The ranges were given around the selection when they hold it (none: the editor's own steps apply anywhere).
private fun stillAbout(ranges: List<TextRange>, span: Span): Boolean {
    val innermost = ranges.firstOrNull() ?: return true
    return span in innermost
}

private fun selectionOrCaret(document: Document): Span {
    val selection = document.selectionRange()
    return selection?.let { Span(it.first, it.second) } ?: Span(document.caret, document.caret)
}

/** A stretch of text between two positions. */
private data class Span(val start: TextPos, val end: TextPos) {
    companion object {
        fun from(range: TextRange) =
            Span(TextPos(range.startLine, range.startColumn), TextPos(range.endLine, range.endColumn))
    }
}

private operator fun TextRange.contains(span: Span): Boolean {
    val outer = Span.from(this)
    return outer.start <= span.start && span.end <= outer.end
}
