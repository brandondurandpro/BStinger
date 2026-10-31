package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** What the user sees when the SSH link to a remote server breaks. */
@OptIn(ExperimentalTestApi::class)
class RemoteDevelopmentChaosE2eTest {
    private val project = SampleProject.standard()

    @BeforeTest
    fun prepare() {
        RemoteTestEnvironment.prepare()
    }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the editor keeps working after the SSH server restarts`() =
        remoteUiTest {
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            openFromTree("README.md")

            // The link goes down: the tunnel and the client must both recover, without losing the daemon.
            RemoteTestEnvironment.ssh.stop()
            awaitReconnecting()
            RemoteTestEnvironment.ssh.start()
            awaitReconnected()

            openFromTree("docs/guide.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.Q)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitCondition { project.read("docs/guide.md").startsWith("qGuide") }
        }

    @Test
    fun `a changed host key is shown instead of reconnecting forever`() =
        remoteUiTest {
            launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)

            RemoteTestEnvironment.ssh.restartWithNewHostKey()
            awaitStatusContains("Host key", timeout = 30_000)
            awaitTag(Tags.STATUS_CONNECTION)
        }
}
