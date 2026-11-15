package org.example.kotlineditor.unit

import org.example.kotlineditor.e2e.FakeInstall
import org.example.kotlineditor.e2e.FakeRelease
import org.example.kotlineditor.e2e.FakeReleaseServer
import org.example.kotlineditor.remote.VerifiedDownload
import org.example.kotlineditor.state.AppUpdater
import org.example.kotlineditor.state.AppVersion
import org.example.kotlineditor.state.AvailableRelease
import org.example.kotlineditor.state.ReleaseAsset
import org.example.kotlineditor.state.RunningInstances
import org.example.kotlineditor.state.SwapJob
import org.example.kotlineditor.state.UpdateState
import org.example.kotlineditor.state.UpdateSwap
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state of the update shared by the windows, against a fake install, a loopback server and a recorder in place of
 * the installer. It does not use the IDE's server, so it has no chaos test.
 */
class AppUpdaterTest {
    private val others = AtomicInteger()
    private val server = FakeReleaseServer()
    private val install = FakeInstall.create()
    private val work: File = Files.createTempDirectory("app-updater").toFile()
    private val updates = File(work, "updates")
    private val jobs = CopyOnWriteArrayList<SwapJob>()
    private val updaters = ArrayList<AppUpdater>()

    @AfterTest
    fun cleanup() {
        updaters.forEach(AppUpdater::close)
        server.close()
        install.close()
        work.deleteRecursively()
    }

    private fun updater(failInstaller: Boolean = false): AppUpdater {
        val installer: (SwapJob) -> Unit = { job ->
            if (failInstaller) {
                throw IOException(
                    "no shell",
                )
            } else {
                jobs.add(job)
            }
        }
        val created =
            AppUpdater(
                install.environment,
                updates,
                { server.base },
                VerifiedDownload(),
                installer,
                processId = { PID },
                otherInstances = { others.get() },
            )
        updaters.add(created)
        return created
    }

    private fun publish(version: String, padBytes: Int = 0): AvailableRelease {
        val archive = FakeRelease.archive(version, work, padBytes)
        val tag = "v$version"
        server.publish(tag, archive)
        val asset = ReleaseAsset(archive.name, archive.length())
        return AvailableRelease(tag, checkNotNull(AppVersion.parse(tag)), listOf(asset))
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) LockSupport.parkNanos(POLL_NANOS)
        assertTrue(condition(), "timed out waiting for $what")
    }

    private fun AppUpdater.awaitReady() = awaitUntil("ready, state is $state") { state is UpdateState.Ready }

    private fun assetRequests(): Int = server.requests.count { it.endsWith(FakeRelease.kind.extension) }

    @Test
    fun `two windows asking for the update start one download`() {
        val release = publish("0.3.0", padBytes = PADDING)
        val updater = updater()
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        updater.start(release)
        assertTrue(gate.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
        // The other window clicks Update while the first download runs.
        updater.start(release)
        assertIs<UpdateState.Downloading>(updater.state)
        gate.release()
        updater.awaitReady()
        assertEquals(1, assetRequests(), "one download: ${server.requests}")
        assertEquals("old", install.version())
    }

    @Test
    fun `the release being downloaded stays pinned when another one is found`() {
        val first = publish("0.3.0", padBytes = PADDING)
        val newer = publish("0.4.0")
        val updater = updater()
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        updater.start(first)
        assertTrue(gate.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
        updater.start(newer)
        assertEquals(first, (updater.state as UpdateState.Pinned).release)
        gate.release()
        updater.awaitReady()
        assertEquals(first, (updater.state as UpdateState.Pinned).release)
        updater.start(newer)
        assertEquals(first, (updater.state as UpdateState.Pinned).release, "a ready update is not replaced")
        assertEquals(1, assetRequests())
    }

    @Test
    fun `cancelling stops the download and deletes the partial file, then the update can start again`() {
        val release = publish("0.3.0", padBytes = PADDING)
        val updater = updater()
        val gate = server.holdNextDownload(afterBytes = HOLD_AFTER)
        updater.start(release)
        assertTrue(gate.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val directory = File(updates, "0.3.0")
        assertTrue(directory.list().orEmpty().any { it.endsWith(".part") }, "a partial file while downloading")
        updater.cancel()
        assertEquals(UpdateState.Idle, updater.state)
        awaitUntil("the partial file gone") { directory.list().orEmpty().none { it.endsWith(".part") } }
        assertEquals(emptyList(), directory.list().orEmpty().toList())
        gate.release()
        updater.start(release)
        updater.awaitReady()
        assertEquals(
            "0.3.0",
            File(install.root.parentFile, install.root.name + ".update/${FakeRelease.VERSION_FILE}").readText().trim(),
        )
    }

    @Test
    fun `a file already downloaded and verified is reused, and what other versions left is deleted`() {
        val release = publish("0.3.0")
        val first = updater()
        first.start(release)
        first.awaitReady()
        File(updates, "0.1.0").mkdirs()
        File(updates, "0.1.0/old.tar.gz").writeText("old")
        File(updates, "leftover.txt").writeText("x")
        File(updates, UpdateSwap.LOG_NAME).writeText("log")
        val second = updater()
        second.start(release)
        second.awaitReady()
        assertEquals(1, assetRequests(), "the verified file is not downloaded again")
        assertFalse(File(updates, "0.1.0").exists())
        assertFalse(File(updates, "leftover.txt").exists())
        assertTrue(File(updates, UpdateSwap.LOG_NAME).exists(), "the log of the last install is kept")
    }

    @Test
    fun `a failure is kept with its message, and Retry runs again`() {
        val release = publish("0.3.0")
        val updater = updater()
        server.assetStatus = NOT_FOUND
        updater.start(release)
        awaitUntil("the failure") { updater.state is UpdateState.Failed }
        val failed = updater.state as UpdateState.Failed
        assertTrue(failed.message.isNotBlank())
        server.assetStatus = null
        updater.retry()
        updater.awaitReady()
        updater.dismissFailure()
        assertIs<UpdateState.Ready>(updater.state)
    }

    @Test
    fun `the restart goes through ready, restarting and the installer, and can be abandoned`() {
        val release = publish("0.3.0")
        val updater = updater()
        updater.requestRestart()
        assertEquals(UpdateState.Idle, updater.state, "nothing to restart for before the update is ready")
        assertFalse(updater.launchInstaller())
        updater.start(release)
        updater.awaitReady()
        assertFalse(updater.launchInstaller(), "the installer only starts when the windows are closing")
        updater.requestRestart()
        assertTrue(updater.restarting)
        updater.restartAbandoned()
        assertIs<UpdateState.Ready>(updater.state)
        assertFalse(updater.restarting)
        updater.requestRestart()
        assertTrue(updater.launchInstaller())
        val job = jobs.single()
        assertEquals(PID, job.pid)
        assertEquals(install.root, job.installed)
        assertEquals(File(install.root.parentFile, install.root.name + ".update"), job.staged)
        assertEquals(File(install.root.parentFile, install.root.name + ".old"), job.backup)
        assertEquals(listOf(install.launcher.path), job.launcher)
        assertEquals(updates, job.directory)
        assertEquals("old", install.version(), "the running install is never swapped by the IDE itself")
    }

    @Test
    fun `no restart and no installer while another IDE runs from the same install`() {
        val release = publish("0.3.0")
        val updater = updater()
        updater.start(release)
        updater.awaitReady()
        others.set(1)
        updater.requestRestart()
        val blocked = assertIs<UpdateState.Ready>(updater.state)
        assertEquals(AppUpdater.OTHER_INSTANCE, blocked.blocked)
        assertFalse(updater.restarting)
        // The other IDE is closed: the restart goes on. One started while the windows close stops the installer.
        others.set(0)
        updater.requestRestart()
        assertTrue(updater.restarting)
        others.set(1)
        assertFalse(updater.launchInstaller())
        assertTrue(jobs.isEmpty())
        others.set(0)
        assertTrue(updater.launchInstaller())
    }

    @Test
    fun `a program is of the install when it is its file or inside its directory`() {
        val directory = File("/apps/kotlin-editor")
        assertTrue(RunningInstances.isFromInstall("/apps/kotlin-editor/bin/kotlin-editor", directory, true))
        assertTrue(RunningInstances.isFromInstall("/apps/kotlin-editor/lib/runtime/bin/java", directory, true))
        assertFalse(RunningInstances.isFromInstall("/apps/kotlin-editor-2/bin/kotlin-editor", directory, true))
        assertFalse(RunningInstances.isFromInstall("/usr/bin/java", directory, true))
        val image = File("/apps/Kotlin Editor.AppImage")
        assertTrue(RunningInstances.isFromInstall("/apps/Kotlin Editor.AppImage", image, true))
        assertFalse(RunningInstances.isFromInstall("/apps/kotlin editor.appimage", image, true))
        val windows = File("/apps/Kotlin Editor")
        assertTrue(RunningInstances.isFromInstall("/Apps/KOTLIN EDITOR/Kotlin Editor.exe", windows, false))
    }

    @Test
    fun `an installer that cannot start is reported on the error stream, nothing is claimed`() {
        val release = publish("0.3.0")
        val updater = updater(failInstaller = true)
        updater.start(release)
        updater.awaitReady()
        updater.requestRestart()
        assertFalse(updater.launchInstaller())
        assertNull(jobs.firstOrNull())
    }

    private companion object {
        const val PID = 4242L
        const val PADDING = 400_000
        const val HOLD_AFTER = 20_000L
        const val NOT_FOUND = 404
        const val WAIT_SECONDS = 60L
        const val POLL_NANOS = 20_000_000L
    }
}
