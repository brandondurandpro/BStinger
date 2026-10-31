package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NetworkPanelE2eTest {
    private val project = SampleProject.standard()

    private val E2eApp.recording get() = client.network.recording

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun E2eApp.records() = client.network.snapshot()

    private fun E2eApp.readResponse() = records().firstOrNull { it.kind == "Response" && it.method == "Read" }

    private fun ComposeUiTest.pressDevtoolsShortcut() {
        onNodeWithTag(Tags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.D) } } }
        }
    }

    @Test
    fun `the devtools panel is hidden until Ctrl+Alt+Shift+D, then captures the frames`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitNoTag(Tags.TOOL_NETWORK)
            assertFalse(app.recording)

            onNodeWithTag(Tags.TREE).performClick()
            pressDevtoolsShortcut()
            awaitTag(Tags.NETWORK)
            awaitTag(Tags.TOOL_NETWORK)
            assertTrue(app.recording)

            openFromTree("README.md")
            awaitCondition { app.readResponse() != null }
            val response = app.readResponse()!!
            awaitTag(ItemTags.networkRecord(response.seq)).performClick()
            awaitTag(Tags.NETWORK_DETAIL)
            val view = app.project.network
            awaitCondition { view.selected?.seq == response.seq }
            // The detail shows the JSON-RPC message, pretty-printed.
            awaitCondition {
                onAllNodesWithText(""""jsonrpc": "2.0",""", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(Tags.NETWORK_TOTALS).assertTextContains("frames", substring = true)

            // The filter keeps the matching frames only.
            onNodeWithTag(Tags.NETWORK_FILTER).performTextInput("GitStatus")
            awaitNoTag(ItemTags.networkRecord(response.seq))

            // Hidden, the panel stops the capture, and the strip keeps its button.
            onNodeWithTag(Tags.NETWORK_HIDE).performClick()
            awaitNoTag(Tags.NETWORK)
            awaitCondition { !app.recording }
            assertFalse(app.project.toolWindows.isVisible(ToolWindow.Network))
            onNodeWithTag(Tags.TOOL_NETWORK).performClick()
            awaitTag(Tags.NETWORK)
            awaitCondition { app.recording }
        }

    @Test
    fun `pause stops the capture and clear empties it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            pressDevtoolsShortcut()
            awaitTag(Tags.NETWORK)
            openFromTree("README.md")
            awaitCondition { app.records().isNotEmpty() }

            onNodeWithTag(Tags.NETWORK_RECORD).performClick()
            awaitCondition { !app.recording }
            onNodeWithTag(Tags.NETWORK_CLEAR).performClick()
            awaitCondition { app.records().isEmpty() }
            awaitTag(Tags.NETWORK_EMPTY)
            openFromTree("build.gradle.kts")
            assertTrue(app.records().isEmpty())

            onNodeWithTag(Tags.NETWORK_RECORD).performClick()
            awaitCondition { app.recording }
        }
}
