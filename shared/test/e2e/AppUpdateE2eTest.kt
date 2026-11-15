package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.AppExit
import org.example.kotlineditor.state.InstallEnvironment
import org.example.kotlineditor.state.ReleaseAsset
import org.example.kotlineditor.state.ReleaseInfo
import org.example.kotlineditor.state.UpdateState
import org.example.kotlineditor.ui.AppTags
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DOWNLOAD_PADDING = 600_000
private const val HOLD_AFTER = 20_000L
private const val UNSAVED_TIMEOUT = AppExit.SAVE_MILLIS + TIMEOUT
private const val GATE_SECONDS = 30L

/**
 * The update of the IDE from the status bar icon: the dialog, the download of a fake release from a loopback server,
 * its verification, the staging next to a fake install in a temporary directory, and the restart, whose installer is
 * a recorder (nothing real is installed, never the real network). The feature does not use the IDE's server, so it
 * has no chaos test: the failures it can meet are the download ones here (404, refused connection, bad checksum,
 * cancelling); the chaos proxy is only used to make the IDE unable to save a file, when the restart is abandoned.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }
    private val rigs = ArrayList<UpdateRig>()

    @AfterTest
    fun cleanup() {
        rigs.forEach(UpdateRig::close)
        project.delete()
    }

    private fun rig(
        padBytes: Int = 0,
        listed: ((ReleaseAsset) -> List<ReleaseAsset>)? = null,
        environment: ((FakeInstall) -> InstallEnvironment)? = null,
    ): UpdateRig = UpdateRig(padBytes = padBytes, listed = listed, environment = environment).also { rigs.add(it) }

    private fun ComposeUiTest.openDialog() {
        awaitTag(AppTags.UPDATE_AVAILABLE).performClick()
        awaitTag(AppTags.UPDATE_DIALOG)
    }

    private fun ComposeUiTest.hasNoTag(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    @Test
    fun `the icon opens a dialog that says which version is available and which runs`() =
        runComposeUiTest {
            val rig = rig()
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_MESSAGE).assertTextContains("Version 0.3.0 is available", substring = true)
            onNodeWithTag(AppTags.UPDATE_MESSAGE).assertTextContains("You are running 0.2.0", substring = true)
            onNodeWithTag(AppTags.UPDATE_NOTE).assertTextContains("restarts", substring = true)
            awaitTag(AppTags.UPDATE_INSTALL)
            awaitTag(AppTags.UPDATE_LATER)
            awaitTag(AppTags.UPDATE_NOTES)
        }

    @Test
    fun `Later closes the dialog and changes nothing`() =
        runComposeUiTest {
            val rig = rig()
            val before = DiskSnapshot.take(project)
            launchWithProject(project, updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_LATER).performClick()
            awaitNoTag(AppTags.UPDATE_DIALOG)
            awaitTag(AppTags.UPDATE_AVAILABLE)
            assertEquals(emptyList(), rig.server.requests.toList(), "nothing was requested")
            assertFalse(rig.updatesDirectory.exists(), "nothing was downloaded")
            assertFalse(rig.staging.exists())
            assertEquals("old", rig.install.version())
            assertEquals(UpdateState.Idle, rig.updater.state)
            before.assertUnchanged(project)
        }

    @Test
    fun `Release notes opens the page of the release in the browser and keeps the dialog`() =
        runComposeUiTest {
            val rig = rig()
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_NOTES).performClick()
            awaitCondition { rig.opened.isNotEmpty() }
            assertEquals(
                listOf("https://github.com/brandondurandpro/BStinger/releases/tag/v0.3.0"),
                rig.opened.toList(),
            )
            awaitTag(AppTags.UPDATE_DIALOG)
            assertEquals(emptyList(), rig.server.requests.toList())
        }

    @Test
    fun `Escape is Later`() =
        runComposeUiTest {
            val rig = rig()
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_DIALOG).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AppTags.UPDATE_DIALOG)
            assertEquals(emptyList(), rig.server.requests.toList())
        }

    @Test
    fun `Update downloads, verifies and stages the release, then Restart closes the IDE and starts the installer`() =
        runComposeUiTest {
            val rig = rig()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, updates = rig.notifier)
            val closed = AtomicBoolean(false)
            runOnUiThread { app.state.onClosed = { closed.set(true) } }
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            onNodeWithTag(AppTags.UPDATE_MESSAGE).assertTextContains("Version 0.3.0 is ready", substring = true)
            assertEquals(listOf(rig.archive.name), rig.downloaded(), "the verified archive is kept, nothing else")
            assertEquals("0.3.0", File(rig.staging, FakeRelease.VERSION_FILE).readText().trim())
            assertEquals("old", rig.install.version(), "the running install is not touched")
            assertTrue(rig.jobs.isEmpty())
            assertFalse(closed.get())
            onNodeWithTag(AppTags.UPDATE_RESTART).performClick()
            awaitCondition(UNSAVED_TIMEOUT) { closed.get() }
            // The desktop app starts the installer as the last window closes.
            assertTrue(rig.updater.launchInstaller())
            val job = rig.jobs.single()
            assertEquals(rig.install.root, job.installed)
            assertEquals(rig.staging, job.staged)
            assertEquals(listOf(rig.install.launcher.path), job.launcher)
            assertEquals(rig.updatesDirectory, job.directory)
            assertEquals("old", rig.install.version(), "only the installer swaps, after the IDE has exited")
            before.assertUnchanged(project)
        }

    @Test
    fun `Cancel stops the download for real, deletes the partial file, and Update starts again`() =
        runComposeUiTest {
            val rig = rig(padBytes = DOWNLOAD_PADDING)
            launchApp(updates = rig.notifier)
            val gate = rig.server.holdNextDownload(HOLD_AFTER)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_PROGRESS)
            assertTrue(gate.reached.await(GATE_SECONDS, TimeUnit.SECONDS))
            assertTrue(rig.downloaded().any { it.endsWith(".part") })
            awaitTag(AppTags.UPDATE_CANCEL).performClick()
            awaitTag(AppTags.UPDATE_INSTALL)
            awaitCondition { rig.downloaded().none { it.endsWith(".part") } }
            assertEquals(emptyList(), rig.downloaded())
            assertEquals(UpdateState.Idle, rig.updater.state)
            gate.release()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            assertEquals(listOf(rig.archive.name), rig.downloaded())
        }

    @Test
    fun `Escape while downloading cancels the download`() =
        runComposeUiTest {
            val rig = rig(padBytes = DOWNLOAD_PADDING)
            launchApp(updates = rig.notifier)
            val gate = rig.server.holdNextDownload(HOLD_AFTER)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_PROGRESS)
            assertTrue(gate.reached.await(GATE_SECONDS, TimeUnit.SECONDS))
            onNodeWithTag(AppTags.UPDATE_DIALOG).performKeyInput { pressKey(Key.Escape) }
            awaitTag(AppTags.UPDATE_INSTALL)
            awaitCondition { rig.downloaded().none { it.endsWith(".part") } }
            gate.release()
        }

    @Test
    fun `a download that fails shows why, never silently, and Retry succeeds once it can`() =
        runComposeUiTest {
            val rig = rig()
            launchApp(updates = rig.notifier)
            rig.server.assetStatus = 404
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_ERROR)
            onNodeWithTag(AppTags.UPDATE_ERROR).assertTextContains("The update failed", substring = true)
            onNodeWithTag(AppTags.UPDATE_ERROR).assertTextContains("no file at", substring = true)
            assertEquals(emptyList(), rig.downloaded())
            rig.server.assetStatus = null
            onNodeWithTag(AppTags.UPDATE_RETRY).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            assertEquals(1, rig.assetRequests() - 1, "the failed request and the retry: ${rig.server.requests}")
        }

    @Test
    fun `a server that cannot be reached shows the error, and Retry works once it is back`() =
        runComposeUiTest {
            val rig = rig()
            val dead = FakeReleaseServer()
            val deadBase = dead.base
            dead.close()
            val liveBase = rig.downloadBase.get()
            rig.downloadBase.set(deadBase)
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_ERROR)
            onNodeWithTag(AppTags.UPDATE_ERROR).assertTextContains("Cannot download", substring = true)
            rig.downloadBase.set(liveBase)
            onNodeWithTag(AppTags.UPDATE_RETRY).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
        }

    @Test
    fun `Close after a failure goes back to the offer`() =
        runComposeUiTest {
            val rig = rig()
            launchApp(updates = rig.notifier)
            rig.server.assetStatus = 500
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_ERROR)
            onNodeWithTag(AppTags.UPDATE_CLOSE).performClick()
            awaitNoTag(AppTags.UPDATE_DIALOG)
            openDialog()
            awaitTag(AppTags.UPDATE_INSTALL)
            assertTrue(hasNoTag(AppTags.UPDATE_ERROR))
        }

    @Test
    fun `a corrupted download shows the error and is deleted`() =
        runComposeUiTest {
            val rig = rig()
            rig.server.publish(rig.tag, rig.archive, checksum = "f".repeat(HASH_LENGTH))
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_ERROR)
            onNodeWithTag(AppTags.UPDATE_ERROR).assertTextContains("corrupted", substring = true)
            assertEquals(emptyList(), rig.downloaded(), "neither the file nor a partial one is kept")
            assertFalse(rig.staging.exists())
            assertEquals("old", rig.install.version())
        }

    @Test
    fun `a release without a file for this system sends to its page`() =
        runComposeUiTest {
            val rig = rig(listed = { emptyList() })
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_REASON).assertTextContains("no download", substring = true)
            assertTrue(hasNoTag(AppTags.UPDATE_INSTALL))
            onNodeWithTag(AppTags.UPDATE_DOWNLOAD_PAGE).performClick()
            awaitCondition { rig.opened.isNotEmpty() }
            assertEquals(
                listOf("https://github.com/brandondurandpro/BStinger/releases/tag/v0.3.0"),
                rig.opened.toList(),
            )
            assertEquals(emptyList(), rig.server.requests.toList())
        }

    @Test
    fun `an install that cannot update itself says so and sends to the release page`() =
        runComposeUiTest {
            val rig = rig(environment = { it.environment.copy(launcher = null) })
            launchApp(updates = rig.notifier)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_REASON).assertTextContains("not an installed one", substring = true)
            onNodeWithTag(AppTags.UPDATE_DOWNLOAD_PAGE).performClick()
            awaitCondition { rig.opened.isNotEmpty() }
            assertEquals("https://github.com/brandondurandpro/BStinger/releases/tag/v0.3.0", rig.opened.single())
            assertEquals(emptyList(), rig.server.requests.toList())
        }

    @Test
    fun `a newer release found during the download does not change the one being downloaded`() =
        runComposeUiTest {
            val rig = UpdateRig(padBytes = DOWNLOAD_PADDING, interval = java.time.Duration.ofMillis(CHECK_MILLIS))
            rigs.add(rig)
            launchApp(updates = rig.notifier)
            val gate = rig.server.holdNextDownload(HOLD_AFTER)
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_PROGRESS)
            assertTrue(gate.reached.await(GATE_SECONDS, TimeUnit.SECONDS))
            rig.releases = listOf(ReleaseInfo("v0.4.0", draft = false)) + rig.releases
            awaitCondition { rig.notifier.available?.tag == "v0.4.0" }
            onNodeWithTag(AppTags.UPDATE_MESSAGE).assertTextContains("0.3.0", substring = true)
            gate.release()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            onNodeWithTag(AppTags.UPDATE_MESSAGE).assertTextContains("0.3.0", substring = true)
            assertEquals("0.3.0", File(rig.staging, FakeRelease.VERSION_FILE).readText().trim())
        }

    @Test
    fun `a restart the user cancels installs nothing, and the next click proposes it again without downloading`() =
        runComposeUiTest {
            val rig = rig()
            val app = launchWithProject(project, chaos = true, updates = rig.notifier)
            val closed = AtomicBoolean(false)
            runOnUiThread { app.state.onClosed = { closed.set(true) } }
            // The IDE cannot save this edit: closing will offer to keep the IDE open.
            edit(app, "README.md")
            app.network.goDown()
            awaitReconnecting()
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            onNodeWithTag(AppTags.UPDATE_RESTART).performClick()
            awaitTag(AppTags.UNSAVED_DIALOG, timeout = UNSAVED_TIMEOUT)
            onNodeWithTag(AppTags.UNSAVED_CANCEL).performClick()
            awaitNoTag(AppTags.UNSAVED_DIALOG)
            awaitCondition { rig.updater.state is UpdateState.Ready }
            assertFalse(closed.get(), "the IDE stayed open")
            assertFalse(rig.updater.launchInstaller(), "no installer for an abandoned restart")
            assertTrue(rig.jobs.isEmpty())
            assertEquals("old", rig.install.version())
            // Back to "ready, restart to install": the dialog proposes the restart, nothing is downloaded again.
            awaitTag(AppTags.UPDATE_RESTART)
            assertEquals(1, rig.assetRequests())
            app.network.comeBack()
            awaitReconnected()
            onNodeWithTag(AppTags.UPDATE_RESTART).performClick()
            awaitCondition(UNSAVED_TIMEOUT) { closed.get() }
            assertTrue(rig.updater.launchInstaller())
            assertEquals(1, rig.assetRequests())
        }

    @Test
    fun `Restart is refused, and says why, while another IDE runs from the same install`() =
        runComposeUiTest {
            val rig = rig()
            val app = launchApp(updates = rig.notifier)
            val closed = AtomicBoolean(false)
            runOnUiThread { app.state.onClosed = { closed.set(true) } }
            openDialog()
            onNodeWithTag(AppTags.UPDATE_INSTALL).performClick()
            awaitTag(AppTags.UPDATE_RESTART, timeout = TIMEOUT * 3)
            rig.otherInstances.set(1)
            onNodeWithTag(AppTags.UPDATE_RESTART).performClick()
            awaitTag(AppTags.UPDATE_BLOCKED).assertTextContains("Another Kotlin Editor is running", substring = true)
            assertFalse(closed.get(), "the IDE stays open")
            assertFalse(rig.updater.launchInstaller())
            assertEquals("old", rig.install.version())
            // The other IDE is closed: the same button restarts.
            rig.otherInstances.set(0)
            onNodeWithTag(AppTags.UPDATE_RESTART).performClick()
            awaitCondition(UNSAVED_TIMEOUT) { closed.get() }
            assertTrue(rig.updater.launchInstaller())
            assertEquals(1, rig.jobs.size)
        }

    private companion object {
        const val HASH_LENGTH = 64
        const val CHECK_MILLIS = 100L
    }
}
