package org.example.kotlineditor.e2e

import org.example.kotlineditor.remote.VerifiedDownload
import org.example.kotlineditor.state.AppUpdater
import org.example.kotlineditor.state.InstallEnvironment
import org.example.kotlineditor.state.ReleaseAsset
import org.example.kotlineditor.state.ReleaseInfo
import org.example.kotlineditor.state.ReleaseSource
import org.example.kotlineditor.state.SwapJob
import org.example.kotlineditor.state.UpdateNotifier
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val STALL_MILLIS = 5_000L
private const val FAKE_PID = 4242L

/**
 * What the update tests share: a fake release of [newVersion] on a loopback server, a fake install in a temporary
 * directory, a folder of downloads in another, and a recorder in place of the installer that would start the helper
 * script. Nothing real is installed, and the network is never used.
 */
class UpdateRig(
    val current: String = "0.2.0",
    val newVersion: String = "0.3.0",
    padBytes: Int = 0,
    environment: ((FakeInstall) -> InstallEnvironment)? = null,
    /** The assets the release lists; the one of the fake archive by default. */
    listed: ((ReleaseAsset) -> List<ReleaseAsset>)? = null,
    interval: Duration = Duration.ofHours(1),
) : AutoCloseable {
    val server = FakeReleaseServer()
    val install = FakeInstall.create()
    private val work: File = Files.createTempDirectory("e2e-update-work").toFile()
    val updatesDirectory: File = File(work, "updates")
    val tag = "v$newVersion"

    /** The installers that were started, in order: what the helper script would have been given. */
    val jobs = CopyOnWriteArrayList<SwapJob>()

    /** How many other IDEs run from the fake install (started twice, or by the `ide` command). */
    val otherInstances = AtomicInteger()

    /** The pages opened in the browser. */
    val opened = CopyOnWriteArrayList<String>()

    /** The base of the downloads, replaceable to point at a dead server. */
    val downloadBase = AtomicReference(server.base)
    val archive: File = FakeRelease.archive(newVersion, work, padBytes)
    private val asset = ReleaseAsset(archive.name, archive.length())

    /** The releases the stand-in of GitHub lists. */
    @Volatile
    var releases: List<ReleaseInfo> = listOf(ReleaseInfo(tag, draft = false, assets = (listed ?: ::listOf)(asset)))

    /** The staging folder the update unpacks into, next to the fake install. */
    val staging: File get() = File(install.root.parentFile, install.root.name + ".update")

    val updater =
        AppUpdater(
            environment?.invoke(install) ?: install.environment,
            updatesDirectory,
            { downloadBase.get() },
            VerifiedDownload(stallMillis = STALL_MILLIS),
            jobs::add,
            processId = { FAKE_PID },
            otherInstances = { otherInstances.get() },
        )
    val notifier = UpdateNotifier(current, ReleaseSource { releases }, interval, opened::add, updater)

    init {
        server.publish(tag, archive)
    }

    /** The downloaded archives and partial files of the version in the downloads folder. */
    fun downloaded(): List<String> = File(updatesDirectory, newVersion).list()?.toList().orEmpty()

    /** How many times the archive was requested from the server. */
    fun assetRequests(): Int = server.requests.count { it.endsWith(archive.name) }

    override fun close() {
        updater.close()
        server.close()
        install.close()
        work.deleteRecursively()
    }
}
