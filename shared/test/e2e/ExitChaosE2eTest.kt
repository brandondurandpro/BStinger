package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.AppExit
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// The bounded wait of each save on exit, and some margin for a slow runner.
private const val UNSAVED_DIALOG_TIMEOUT = AppExit.SAVE_MILLIS + TIMEOUT

/**
 * Closing the IDE while the server is out of reach: every file is tried, the wait is bounded, and the files that
 * could not be saved are shown, to save once the server is back, to drop, or to keep open.
 */
@OptIn(ExperimentalTestApi::class)
class ExitChaosE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Edits the files of [EDITED_ON_EXIT], cuts the network and closes the window; waits for the files the dialog
    // says could not be saved.
    private fun ComposeUiTest.closeOffline(app: E2eApp): ClosedOffline {
        val documents = EDITED_ON_EXIT.associateWith { edit(app, it) }
        val before = DiskSnapshot.take(project)
        app.network.goDown()
        awaitReconnecting()
        val closed = closeWindow(app)
        awaitTag(AppTags.UNSAVED_DIALOG, timeout = UNSAVED_DIALOG_TIMEOUT)
        for (path in EDITED_ON_EXIT) awaitTag(AppTags.unsavedFile(path))
        assertFalse(closed.get(), "The IDE closed with files it could not save")
        before.assertUnchanged(project)
        return ClosedOffline(documents, before, closed)
    }

    private class ClosedOffline(
        val documents: Map<String, Document>,
        val before: DiskSnapshot,
        val closed: AtomicBoolean,
    )

    @Test
    fun `closing the IDE offline lists the files it could not save, and Retry saves them once the server is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val offline = closeOffline(app)
            app.network.comeBack()
            awaitReconnected()
            onNodeWithTag(AppTags.UNSAVED_RETRY).performClick()
            awaitCondition(UNSAVED_DIALOG_TIMEOUT) { offline.closed.get() }
            awaitNoTag(AppTags.UNSAVED_DIALOG)
            // Nothing lost: every edit is on disk, written once, and nothing else changed.
            for ((path, document) in offline.documents) assertEquals(document.text(), project.read(path))
            offline.before.assertUnchanged(project, except = EDITED_ON_EXIT.toSet())
        }

    @Test
    fun `Retry while the server is still out of reach lists the files again, and Close Anyway closes without them`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val offline = closeOffline(app)
            onNodeWithTag(AppTags.UNSAVED_RETRY).performClick()
            val exiting = app.state.exiting
            awaitCondition(UNSAVED_DIALOG_TIMEOUT) { exiting.failure?.saving == false }
            for (path in EDITED_ON_EXIT) awaitTag(AppTags.unsavedFile(path))
            assertFalse(offline.closed.get())
            onNodeWithTag(AppTags.UNSAVED_CLOSE_ANYWAY).performClick()
            awaitCondition { offline.closed.get() }
            offline.before.assertUnchanged(project)
        }

    @Test
    fun `Cancel keeps the IDE open with its edits, which are saved once the server is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val offline = closeOffline(app)
            onNodeWithTag(AppTags.UNSAVED_CANCEL).performClick()
            awaitNoTag(AppTags.UNSAVED_DIALOG)
            for (path in EDITED_ON_EXIT) awaitTag(EditorTags.tabModified(path))
            app.network.comeBack()
            awaitReconnected()
            // Closing again, with the server back: everything is saved and the IDE closes.
            val closed = closeWindow(app)
            awaitCondition(UNSAVED_DIALOG_TIMEOUT) { closed.get() }
            assertFalse(offline.closed.get(), "The cancelled close went on")
            for ((path, document) in offline.documents) assertEquals(document.text(), project.read(path))
        }
}
