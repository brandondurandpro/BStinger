package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ServerConnectionE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the IDE keeps working when the connection to the server drops`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            app.server.dropConnections()
            openFromTree("docs/guide.md")
            awaitTag(Tags.line(1)).assertTextContains("Hello from the guide", substring = true)
            onNodeWithTag(Tags.EDITOR).performClick()
            app.server.dropConnections()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.Q)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitCondition { project.read("docs/guide.md").startsWith("qGuide") }
        }

    @Test
    fun `the status bar warns while the server is unreachable`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.server.close()
            awaitTag(Tags.STATUS_CONNECTION).assertTextContains("reconnecting", substring = true)
        }

    @Test
    fun `a server restarted with another token is reported as refusing the IDE, not as reconnecting forever`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            // Reinstalled or reconfigured: same address, new secret.
            app.process.restart(token = "another-token")
            awaitTag(Tags.STATUS_CONNECTION)
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.STATUS_CONNECTION).assertTextContains("disconnected", substring = true)
                }.isSuccess
            }
        }
}
