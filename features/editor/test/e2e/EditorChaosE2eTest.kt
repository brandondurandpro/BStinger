package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SLOW_LINK_MS = 300L
private const val LARGE_FILE_LINES = 200_000
private const val LARGE_SAVE_TIMEOUT = 30_000L

/** Opening, editing and saving files while the network or the server fails, as they do for a remote server. */
@OptIn(ExperimentalTestApi::class)
class EditorChaosE2eTest {
    private val project = SampleProject.standard()
    private val readme = project.read("README.md")

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.typeAtStart(key: Key) {
        onNodeWithTag(EditorTags.EDITOR).performClick()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
            pressKey(key)
        }
    }

    private fun ComposeUiTest.save() {
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
    }

    @Test
    fun `a file saved while the network is down is written once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromTree("README.md")
            app.network.goDown()
            awaitReconnecting()
            typeAtStart(Key.Q)
            save()
            // Still modified, nothing written: the edit is not lost, it waits for the server.
            awaitTag(EditorTags.tabModified("README.md"))
            assertEquals(readme, project.read("README.md"))
            app.network.comeBack()
            awaitCondition { project.read("README.md") == "q$readme" }
            awaitNoTag(EditorTags.tabModified("README.md"))
            awaitReconnected()
        }

    @Test
    fun `a file opened while the link is silently dead shows up once the link is replaced`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            // The connection stays open but nothing goes through, as after a laptop sleep or a dead Wi-Fi.
            app.network.blackHole = true
            onNodeWithTag(TreeTags.treeRow("README.md")).performDoubleClick()
            awaitTag(EditorTags.tab("README.md"))
            awaitReconnecting()
            val tab = app.project.editor.activeTab
            assertIs<TabStatus.Loading>(tab?.status)
            app.network.blackHole = false
            awaitTag(EditorTags.line(0)).assertTextContains("# Demo", substring = true)
            awaitReconnected()
        }

    @Test
    fun `unsaved edits survive a restart of the server and are saved to the new one`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromTree("README.md")
            typeAtStart(Key.Q)
            app.process.stop()
            awaitReconnecting()
            // Editing goes on while the server is down.
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.W) }
            app.process.start()
            awaitReconnected()
            save()
            awaitCondition { project.read("README.md") == "qw$readme" }
            awaitNoTag(EditorTags.tabModified("README.md"))
        }

    @Test
    fun `a save whose answer is lost while the server restarts ends saved, without an error`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromTree("README.md")
            typeAtStart(Key.Q)
            app.network.dropFromServer = true
            save()
            awaitCondition { project.read("README.md") == "q$readme" }
            // The new server has no answer for the write: the disk tells it was made.
            app.process.restart()
            app.network.dropFromServer = false
            app.network.cutAll()
            awaitNoTag(EditorTags.tabModified("README.md"))
            val tab = app.project.editor.activeTab
            val document = tab?.document
            assertNull(document?.saveError)
            assertEquals("q$readme", project.read("README.md"))
        }

    @Test
    fun `typing does not wait for a slow server, and saving still works`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = SLOW_LINK_MS
            openFromTree("README.md")
            typeAtStart(Key.Q)
            waitForIdle()
            // Shown at once, without a round trip to the server.
            onNodeWithTag(EditorTags.line(0)).assertTextContains("q# Demo", substring = true)
            save()
            awaitCondition { project.read("README.md") == "q$readme" }
        }

    @Test
    fun `two saves in a row on a slow link leave the last text on disk, and the tab saved`() =
        runComposeUiTest {
            // Large, so that its write takes the server longer than the short text saved right after it.
            val large = (1..LARGE_FILE_LINES).joinToString("\n") { "line $it of a file large enough to be slow" }
            project.file("large.txt", large)
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = SLOW_LINK_MS
            openFromTree("large.txt")
            typeAtStart(Key.Q)
            save()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.A) }
                pressKey(Key.W)
            }
            save()
            val tab = checkNotNull(app.project.editor.activeTab)
            val document = checkNotNull(tab.document)
            // The last save done: none runs (the stamp is odd while one does) and nothing is left to save. The second
            // save may have been folded into the first, when it was asked before the first one started.
            awaitCondition(LARGE_SAVE_TIMEOUT) {
                val stamp = document.saveStamp
                val saving = stamp % 2 == 1
                stamp > 0 && !saving && !document.isModified
            }
            assertEquals("w", project.read("large.txt"))
            awaitNoTag(EditorTags.tabModified("large.txt"))
        }

    @Test
    fun `a save asked while the previous one is unanswered is sent once that one is answered`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromTree("README.md")
            // The server runs the first save, its answer is lost: the client waits, then reconnects and retries.
            app.network.dropFromServer = true
            val connections = app.network.connectionsAccepted
            typeAtStart(Key.Q)
            save()
            awaitCondition { project.read("README.md") == "q$readme" }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { pressKey(Key.W) }
            save()
            // Two writes in flight together may land in any order: the second one waits for the first answer.
            awaitCondition { project.read("README.md") == "qw$readme" }
            val reconnected = app.network.connectionsAccepted > connections
            assertTrue(reconnected, "The second save was sent before the first answer")
            app.network.dropFromServer = false
            awaitNoTag(EditorTags.tabModified("README.md"))
            assertEquals("qw$readme", project.read("README.md"))
        }
}
