package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.TextRange

private const val HOVER_DELAY_MS = 500L
private const val HOVER_HIDE_MS = 300L

/** The documentation on hover and Ctrl+Q, with the problems under the mouse. */
@Stable
internal class HoverInsight(
    private val scope: CoroutineScope,
    private val requests: InsightRequests,
    private val notices: InsightNotices,
    private val problemsOf: (Document) -> List<Diagnostic>,
) {
    var popup: HoverPopup? by mutableStateOf(null)
        private set

    var popupHovered = false
        private set

    private var hoverJob: Job? = null
    private var hideJob: Job? = null
    private var hoverWord: Pair<Document, TextRange>? = null

    /** The mouse rests at [pos], over [word] (null: not over a word): its documentation comes after a short pause. */
    fun mouseAt(path: String, document: Document, pos: TextPos?, word: TextRange?) {
        val previous = hoverWord
        val sameWord = word != null && previous?.first === document && previous.second == word
        if (sameWord) return
        hoverJob?.cancel()
        hoverWord = word?.let { document to it }
        // The documentation of the previous word goes, unless the mouse goes onto it.
        scheduleHide()
        if (word != null && pos != null) {
            hoverJob =
                scope.launch {
                    delay(HOVER_DELAY_MS)
                    showHover(path, document, pos, TextPos(word.startLine, word.startColumn))
                }
        }
    }

    /** The mouse left the code: the documentation goes, unless the mouse went onto it. */
    fun mouseLeft() {
        hoverJob?.cancel()
        hoverWord = null
        scheduleHide()
    }

    /** The mouse entered ([inside]) or left the documentation popup. */
    fun popupHovered(inside: Boolean) {
        popupHovered = inside
        if (inside) hideJob?.cancel() else scheduleHide()
    }

    /** Ctrl+Q: the documentation of the symbol at the caret. */
    fun quickDocumentation(path: String, document: Document) {
        val caret = document.caret
        val before = TextPos(caret.line, (caret.column - 1).coerceAtLeast(0))
        val word = wordRange(document, caret) ?: wordRange(document, before)
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: caret
        hoverJob?.cancel()
        hoverJob = scope.launch { showHover(path, document, caret, anchor, explicit = true) }
    }

    private suspend fun showHover(
        path: String,
        document: Document,
        pos: TextPos,
        anchor: TextPos,
        explicit: Boolean = false,
    ) {
        val version = document.version
        val problems = problemsOf(document).filter { inside(pos, it.range) }
        val result = requests.askAt(path, document, pos) { servers, at -> servers.hover(at) }
        val markdown = result?.markdown
        val nothing = markdown == null && problems.isEmpty()
        when {
            document.version != version -> {
                // the text changed meanwhile: the answer is about an older one
            }

            nothing && explicit -> {
                notices.notify(document, anchor, "No documentation found")
            }

            nothing -> {
                // a word without documentation shows nothing on hover
            }

            else -> {
                hideJob?.cancel()
                popup = HoverPopup(document, anchor, markdown, problems)
            }
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        if (popup != null) {
            hideJob =
                scope.launch {
                    delay(HOVER_HIDE_MS)
                    if (!popupHovered) popup = null
                }
        }
    }

    /** Closes the documentation. */
    fun dismiss() {
        hoverJob?.cancel()
        hideJob?.cancel()
        hoverWord = null
        popupHovered = false
        popup = null
    }
}
