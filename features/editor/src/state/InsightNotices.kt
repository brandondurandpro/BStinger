package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val NOTICE_MS = 3_000L
private const val INDEXING_CHECK_MS = 200L

/** The short messages of the code intelligence shown over the code ("No usages found", "Indexing the project…"). */
@Stable
internal class InsightNotices(private val scope: CoroutineScope, private val indexing: () -> Boolean) {
    var notice: InsightNotice? by mutableStateOf(null)
        private set

    private var noticeJob: Job? = null

    /** Shows [text] at [anchor] of [document] for a few seconds. */
    fun notify(document: Document, anchor: TextPos, text: String) {
        noticeJob?.cancel()
        notice = InsightNotice(document, anchor, text)
        noticeJob =
            scope.launch {
                delay(NOTICE_MS)
                notice = null
            }
    }

    /** Closes the message. */
    fun dismiss() {
        noticeJob?.cancel()
        notice = null
    }

    /** Hides the message, which an edit makes stale. */
    fun hide() {
        notice = null
    }

    /** Runs [request]; while it waits because a language server indexes the project, says so at [anchor]. */
    suspend fun <T> whileIndexing(document: Document, anchor: TextPos, request: suspend () -> T): T {
        val waiting =
            scope.launch {
                while (!indexing()) delay(INDEXING_CHECK_MS)
                noticeJob?.cancel()
                notice = InsightNotice(document, anchor, INDEXING)
            }
        try {
            return request()
        } finally {
            waiting.cancel()
            if (notice?.text == INDEXING) dismiss()
        }
    }
}
