package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.text
import org.example.kotlineditor.remote.RemoteBundles
import org.example.kotlineditor.remote.RemoteSession
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TerminalTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * IDEs of several builds sharing the daemon of a host: the same protocol shares it, another protocol replaces it,
 * but only once the user agreed when it still has sessions.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteDaemonSharingE2eTest {
    private val project = SampleProject.standard()

    @BeforeTest
    fun prepare() {
        RemoteTestEnvironment.prepare()
    }

    @AfterTest
    fun cleanup() {
        project.delete()
        // A daemon left pretending to speak another protocol would stop the next tests at the question.
        val protocol = RemoteTestEnvironment.daemonState()?.getProperty("protocol")
        if (protocol != null && protocol != "${Protocol.VERSION}") RemoteTestEnvironment.stopDaemon()
    }

    @Test
    fun `an IDE of another build shares the daemon, and the terminal of the first IDE goes on`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            openTerminal(app)
            runCommand("echo before-update")
            awaitCondition { printedLines(app, "before-update") > 0 }
            val firstPid = RemoteTestEnvironment.daemonPid()
            val runningBuild = RemoteTestEnvironment.runningBuild()

            // An updated IDE (same protocol, another bundle) connects to the host while the first one works there.
            val otherBuild =
                RemoteTestEnvironment.withRebuiltBundle { rebuilt ->
                    val other = runBlocking { RemoteSession.connect(RemoteTestEnvironment.sshTarget(project.path)) }
                    other.close()
                    RemoteBundles.buildId(rebuilt.toFile())
                }

            assertEquals(firstPid, RemoteTestEnvironment.daemonPid(), "the daemon must not be replaced")
            assertEquals(runningBuild, RemoteTestEnvironment.runningBuild())
            assertTrue(otherBuild in RemoteTestEnvironment.installedBuilds(), "installed side by side")
            runCommand("echo after-update")
            awaitCondition { printedLines(app, "after-update") > 0 }
            awaitNoTag(TerminalTags.TERMINAL_LOST)
            assertEquals(1, printedLines(app, "before-update"), "the screen of the shell is still there")
        }

    @Test
    fun `a daemon of another protocol with sessions is replaced only once the user agrees`() =
        remoteUiTest {
            val app = launchApp()
            val oldPid = RemoteTestEnvironment.startOldDaemon(protocol = Protocol.VERSION - 1, sessions = 2)

            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(RemoteTags.REMOTE_REPLACE_QUESTION, timeout = 60_000)
                .assertTextContains("still has 2 sessions", substring = true)
            onNodeWithTag(RemoteTags.REMOTE_REPLACE_QUESTION)
                .assertTextContains("protocol ${Protocol.VERSION - 1}", substring = true)
            onNodeWithTag(RemoteTags.REMOTE_REPLACE_KEEP).performClick()
            awaitNoTag(RemoteTags.REMOTE_REPLACE_QUESTION)
            assertFalse(app.state.isRemote, "keeping the server must not connect")
            assertTrue(isAlive(oldPid), "the old daemon must keep running")
            assertEquals(oldPid, RemoteTestEnvironment.daemonPid())
            awaitTag(RemoteTags.REMOTE_DIALOG)

            // Asked again: this time the user agrees.
            onNodeWithTag(RemoteTags.REMOTE_CONNECT).performClick()
            awaitTag(RemoteTags.REMOTE_REPLACE_CONFIRM, timeout = 60_000).performClick()
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            assertTrue(app.state.isRemote)
            assertFalse(isAlive(oldPid), "the old daemon must be stopped")
            assertEquals("${Protocol.VERSION}", RemoteTestEnvironment.daemonState()?.getProperty("protocol"))
        }

    @Test
    fun `a daemon of another protocol that nobody uses is replaced without asking`() =
        remoteUiTest {
            launchApp()
            val oldPid = RemoteTestEnvironment.startOldDaemon(protocol = Protocol.VERSION - 1, sessions = 0)

            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            awaitNoTag(RemoteTags.REMOTE_REPLACE_QUESTION)
            assertFalse(isAlive(oldPid), "the old daemon must be stopped")
            assertNotEquals(oldPid, RemoteTestEnvironment.daemonPid(), "the daemon must be replaced")
        }

    @Test
    fun `a daemon too old to count its sessions is not replaced without asking`() =
        remoteUiTest {
            launchApp()
            val oldPid = RemoteTestEnvironment.startOldDaemon(protocol = Protocol.VERSION - 1, sessions = null)

            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(RemoteTags.REMOTE_REPLACE_QUESTION, timeout = 60_000)
                .assertTextContains("may still have sessions", substring = true)
            assertTrue(isAlive(oldPid), "the old daemon must keep running until the user agrees")
            onNodeWithTag(RemoteTags.REMOTE_REPLACE_CONFIRM).performClick()
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            assertFalse(isAlive(oldPid), "the old daemon must be stopped")
        }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `the bundles of other builds unused for a week are removed, the others are kept`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            disconnectFromTopBar(app)
            val unused = installAnotherBuild()
            val recent = installAnotherBuild()
            RemoteTestEnvironment.ageBundle(unused, days = 8)
            RemoteTestEnvironment.ageBundle(recent, days = 2)

            connectAgainFromDialog()
            awaitTag(TreeTags.treeRow("src"), timeout = 60_000)
            val installed = RemoteTestEnvironment.installedBuilds()
            assertFalse(unused in installed, "a bundle unused for a week must be removed: $installed")
            assertTrue(recent in installed, "a bundle used two days ago must stay: $installed")
            assertTrue(RemoteTestEnvironment.runningBuild() in installed, "the running daemon's bundle must stay")
        }

    // Another IDE build installs its bundle on the host (sharing the daemon) and leaves; returns its build id.
    private fun installAnotherBuild(): String =
        RemoteTestEnvironment.withRebuiltBundle { rebuilt ->
            runBlocking { RemoteSession.connect(RemoteTestEnvironment.sshTarget(project.path)) }.close()
            RemoteBundles.buildId(rebuilt.toFile())
        }

    private fun ComposeUiTest.connectAgainFromDialog() {
        onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
        awaitTag(RemoteTags.REMOTE_DIALOG)
        onNodeWithTag(RemoteTags.REMOTE_CONNECT).performClick()
    }

    private fun ComposeUiTest.disconnectFromTopBar(app: E2eApp) {
        onNodeWithTag(AppTags.TOP_REMOTE).performClick()
        awaitTag(AppTags.REMOTE_DISCONNECT).performClick()
        awaitTag(AppTags.WELCOME)
        awaitCondition { !app.state.isRemote }
    }

    private fun screenLines(app: E2eApp): List<String> =
        app.project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // Lines a command printed: lines echoing the command itself are skipped.
    private fun printedLines(app: E2eApp, text: String): Int =
        screenLines(app).count { line -> "echo" !in line && line.endsWith(text) }

    private fun ComposeUiTest.openTerminal(app: E2eApp) {
        onNodeWithTag(AppTags.TOOL_TERMINAL).performClick()
        awaitTag(TerminalTags.TERMINAL_INPUT, timeout = 30_000)
        awaitCondition { screenLines(app).any { it.isNotBlank() } }
    }

    private fun ComposeUiTest.runCommand(command: String) {
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performClick()
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performTextInput(command)
        onNodeWithTag(TerminalTags.TERMINAL_INPUT).performKeyInput { pressKey(Key.Enter) }
    }
}
