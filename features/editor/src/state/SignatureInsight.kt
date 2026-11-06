package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SIGNATURE_DELAY_MS = 150L

/** The parameter info: opened by `(`, `, ` and Ctrl+P, it follows the caret while it is shown. */
@Stable
internal class SignatureInsight(
    private val scope: CoroutineScope,
    private val requests: InsightRequests,
    private val notices: InsightNotices,
) {
    var popup: SignaturePopup? by mutableStateOf(null)
        private set

    private var signatureJob: Job? = null

    /** Asks the parameter info at the caret; typing ([explicit] false) asks it after a short pause. */
    fun request(path: String, document: Document, explicit: Boolean) {
        signatureJob?.cancel()
        val caret = document.caret
        val version = document.version
        signatureJob =
            scope.launch {
                if (!explicit) delay(SIGNATURE_DELAY_MS)
                val help = requests.askAt(path, document, caret) { servers, at -> servers.signatureHelp(at) }
                val unchanged = document.version == version && document.caret == caret
                val shown = help?.takeIf { it.signatures.isNotEmpty() }
                if (unchanged) {
                    popup = shown?.let { SignaturePopup(document, caret, it) }
                    if (shown == null && explicit) notices.notify(document, caret, "No parameters to show")
                }
            }
    }

    /** Closes the parameter info. */
    fun dismiss() {
        signatureJob?.cancel()
        popup = null
    }
}
