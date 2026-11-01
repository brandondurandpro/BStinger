package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Remote Development through the real UI, the real OpenSSH client and a real SSH server. */
@OptIn(ExperimentalTestApi::class)
class RemoteDevelopmentE2eTest {
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
    fun `installs the server, opens a remote project and saves a file there`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            assertTrue(app.state.isRemote, "the IDE should show the remote server")
            onNodeWithTag(Tags.STATUS_REMOTE).assertTextContains("Remote: tester@127.0.0.1", substring = true)

            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.Q)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitCondition { project.read("README.md").startsWith("q# Demo") }
        }

    @Test
    fun `cancelling the dialog leaves the IDE on the local server`() =
        remoteUiTest {
            val app = launchApp()
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            onNodeWithTag(Tags.REMOTE_CANCEL).performClick()
            awaitNoTag(Tags.REMOTE_DIALOG)
            assertFalse(app.state.isRemote, "cancelling must not connect")
            awaitTag(Tags.WELCOME)
        }

    @Test
    fun `an empty host is reported without running ssh`() =
        remoteUiTest {
            val app = launchApp()
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            onNodeWithTag(Tags.REMOTE_NEW_HOST).performClick()
            awaitTag(Tags.REMOTE_HOST)
            onNodeWithTag(Tags.REMOTE_HOST).performTextClearance()
            onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
            awaitTag(Tags.REMOTE_ERROR).assertTextContains("Enter a host", substring = true)
            assertFalse(app.state.isRemote)
        }

    @Test
    fun `the top bar shows the remote host and disconnects from it`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            onNodeWithTag(Tags.TOP_REMOTE).assertTextContains("tester@127.0.0.1", substring = true)

            onNodeWithTag(Tags.TOP_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DISCONNECT).performClick()
            awaitTag(Tags.WELCOME)
            awaitCondition { !app.state.isRemote }
            awaitNoTag(Tags.TOP_REMOTE)
        }

    @Test
    fun `a missing identity file is reported in the dialog`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path, identity = "/does/not/exist/id_rsa")
            awaitTag(Tags.REMOTE_ERROR, timeout = 30_000)
            assertFalse(app.state.isRemote)
            awaitTag(Tags.REMOTE_DIALOG)
        }

    @Test
    fun `disconnecting returns to the local server`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)

            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            onNodeWithTag(Tags.PROJECT_CLOSE).performClick()
            awaitTag(Tags.WELCOME)
            onNodeWithTag(Tags.REMOTE_DISCONNECT).performClick()
            awaitCondition { !app.state.isRemote }

            // The Open Project dialog browses the local server again.
            openProjectDirectoryFromWelcome(project.path)
            awaitTag(Tags.treeRow("src"))
            assertFalse(app.state.isRemote)
        }

    @Test
    fun `once disconnected, the welcome screen lists the local recent projects, never the remote ones`() =
        remoteUiTest {
            val app = launchApp(chaos = true)
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            onNodeWithTag(Tags.PROJECT_CLOSE).performClick()
            awaitTag(Tags.WELCOME)
            // The daemon is shared by the tests: its list holds their projects too.
            assertTrue(project.path in app.state.recentProjects, "the remote server's recents")

            // A slow link to the local server: its list takes a while to come back.
            app.network.latencyMs = 400
            onNodeWithTag(Tags.REMOTE_DISCONNECT).performClick()
            // From the moment the IDE says it is local, the list is the local one: a remote path shown there would
            // be opened on the local disk, and the screen would still change under the user's click.
            awaitCondition { !app.state.isRemote }
            assertEquals(emptyList(), app.state.recentProjects.toList(), "the local server has no recent project")
            openProjectPickerFromWelcome()
        }

    @Test
    fun `reconnecting reuses the daemon that is already running`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            val firstPid = RemoteTestEnvironment.daemonPid()

            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            onNodeWithTag(Tags.PROJECT_CLOSE).performClick()
            awaitTag(Tags.WELCOME)
            onNodeWithTag(Tags.REMOTE_DISCONNECT).performClick()
            awaitCondition { !app.state.isRemote }

            // The dialog remembers the last connection; connecting again must reuse the daemon.
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            assertEquals(firstPid, RemoteTestEnvironment.daemonPid())
        }

    @Test
    fun `a rebuilt server with the same version number replaces the running daemon`() =
        remoteUiTest {
            val app = launchApp()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.treeRow("src"), timeout = 60_000)
            val firstPid = RemoteTestEnvironment.daemonPid()
            val firstBuild = RemoteTestEnvironment.installedBuild()
            disconnectFromTopBar(app)

            RemoteTestEnvironment.withRebuiltBundle {
                onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
                awaitTag(Tags.REMOTE_DIALOG)
                onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
                awaitTag(Tags.treeRow("src"), timeout = 60_000)
            }
            assertNotEquals(firstBuild, RemoteTestEnvironment.installedBuild(), "the new bundle must be installed")
            assertNotEquals(firstPid, RemoteTestEnvironment.daemonPid(), "the old daemon must be replaced")
            assertTrue(app.state.isRemote)
        }

    @Test
    fun `an unknown host key tells how to trust the host`() =
        remoteUiTest {
            val app = launchApp()
            RemoteTestEnvironment.forgetHostKey()
            connectRemoteTo(RemoteTestEnvironment, project.path)
            awaitTag(Tags.REMOTE_ERROR, timeout = 30_000).assertTextContains("known_hosts", substring = true)
            assertFalse(app.state.isRemote)
            awaitTag(Tags.REMOTE_DIALOG)
        }

    @Test
    fun `cancelling a connection in progress stops ssh`() =
        remoteUiTest {
            val app = launchApp()
            RemoteTestEnvironment.withSlowSsh(delaySeconds = 60) { wrapper ->
                connectRemoteTo(RemoteTestEnvironment, project.path)
                awaitTag(Tags.REMOTE_STATUS)
                awaitCondition { sshProcesses(wrapper) > 0 }

                onNodeWithTag(Tags.REMOTE_CANCEL).performClick()
                awaitNoTag(Tags.REMOTE_DIALOG)
                // Before, the ssh command ran on (an upload for minutes) after the dialog was gone.
                awaitCondition { sshProcesses(wrapper) == 0 }
            }
            assertFalse(app.state.isRemote)
            awaitTag(Tags.WELCOME)
        }

    @Test
    fun `installs the server and opens a project when the login shell is fish`() = installsWithLoginShell("fish")

    @Test
    fun `installs the server and opens a project when the login shell is tcsh`() = installsWithLoginShell("tcsh")

    // ssh runs the commands in the user's login shell: the whole install (upload, unpack, start) must work there.
    private fun installsWithLoginShell(name: String) {
        if (!RemoteTestEnvironment.available) return
        val shell = RemoteTestEnvironment.loginShell(name)
        if (shell == null) {
            println("SKIPPED: $name is not installed")
            return
        }
        RemoteTestEnvironment.withLoginShell(shell) {
            remoteUiTest {
                val app = launchApp()
                val previousBuild = RemoteTestEnvironment.installedBuild()
                // A bundle never installed yet, so the upload and unpacking run in that shell too.
                RemoteTestEnvironment.withRebuiltBundle {
                    connectRemoteTo(RemoteTestEnvironment, project.path)
                    awaitTag(Tags.treeRow("src"), timeout = 60_000)
                }
                assertTrue(app.state.isRemote)
                assertNotEquals(previousBuild, RemoteTestEnvironment.installedBuild(), "installed through $name")
                openFromTree("README.md")
                onNodeWithTag(Tags.EDITOR).performClick()
                onNodeWithTag(Tags.EDITOR).performKeyInput {
                    withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                    pressKey(Key.Q)
                    withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
                }
                awaitCondition { project.read("README.md").startsWith("q# Demo") }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.disconnectFromTopBar(app: E2eApp) {
        onNodeWithTag(Tags.TOP_REMOTE).performClick()
        awaitTag(Tags.REMOTE_DISCONNECT).performClick()
        awaitTag(Tags.WELCOME)
        awaitCondition { !app.state.isRemote }
    }

    private fun runsWrapper(process: ProcessHandle, wrapper: java.nio.file.Path): Boolean {
        val line = process.info().commandLine()
        return line.isPresent && wrapper.toString() in line.get()
    }

    // Processes of this JVM running the ssh wrapper.
    private fun sshProcesses(wrapper: java.nio.file.Path): Int =
        ProcessHandle
            .current()
            .descendants()
            .filter { it.isAlive && runsWrapper(it, wrapper) }
            .count()
            .toInt()
}
