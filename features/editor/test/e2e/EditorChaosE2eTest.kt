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

private const val SLOW_LINK_MS = 300L

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
}
