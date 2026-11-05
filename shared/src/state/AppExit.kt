package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Closing the IDE: the [files] whose edits could not be saved (the server out of reach, a file it refused), shown
 * until the user retries, closes anyway or keeps the IDE open; [saving] while they are being saved again.
 */
data class ExitSaveFailure(val files: List<UnsavedFile>, val saving: Boolean = false)

/**
 * Closing the IDE ([start]): the session and every modified file are saved, each whatever happens to the others and
 * waiting at most [SAVE_MILLIS] (a remote server out of reach must not hold the window for minutes). When some
 * could not be saved, [failure] lists them and the IDE stays open until the user retries successfully ([retry]),
 * closes anyway ([closeAnyway]) or keeps it open ([cancel]); otherwise [finish] runs, then the caller's callback.
 */
@Stable
class AppExit(
    private val scope: CoroutineScope,
    private val project: () -> ProjectState?,
    private val finish: suspend () -> Unit,
) {
    /** The files that could not be saved as the IDE closes; null when none, or when it is not closing. */
    var failure: ExitSaveFailure? by mutableStateOf(null)
        private set

    // What to call once the IDE may close; null when it is not closing.
    private var pending: (() -> Unit)? = null

    /** Saves everything, then calls [onDone] once the IDE may close. */
    fun start(onDone: () -> Unit) {
        // Already closing (saving, or waiting for the user's choice).
        if (pending != null) return
        pending = onDone
        scope.launch { saveThenFinish() }
    }

    /** Saves again the files that could not be saved; the IDE closes if they now are. */
    fun retry() {
        val current = failure ?: return
        if (current.saving) return
        failure = current.copy(saving = true)
        scope.launch { saveThenFinish() }
    }

    /** Closes the IDE without the edits that could not be saved. */
    fun closeAnyway() {
        if (failure == null) return
        failure = null
        scope.launch { finishNow() }
    }

    /** Keeps the IDE open, with the edits that could not be saved. */
    fun cancel() {
        failure = null
        pending = null
    }

    private suspend fun saveThenFinish() {
        val open = project()
        val unsaved = if (open == null) emptyList() else save(open)
        // Kept open meanwhile: nothing more to do.
        if (pending == null) return
        if (unsaved.isEmpty()) {
            failure = null
            finishNow()
        } else {
            failure = ExitSaveFailure(unsaved)
        }
    }

    private suspend fun finishNow() {
        finish()
        val done = pending
        pending = null
        done?.invoke()
    }

    // The session and the modified tabs, saved together through the project's own services: the files that could not.
    private suspend fun save(open: ProjectState): List<UnsavedFile> =
        coroutineScope {
            val session = open.session()
            val sessionSave =
                launch {
                    runCatching { withTimeout(SAVE_MILLIS) { open.services.sessions.save(open.root.path, session) } }
                }
            val unsaved = open.editor.saveAllNow(SAVE_MILLIS)
            sessionSave.join()
            unsaved
        }

    companion object {
        /** How long closing the IDE waits for each save before telling which files could not be saved. */
        const val SAVE_MILLIS = 5_000L
    }
}
