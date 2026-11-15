package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.remote.VerifiedDownload
import java.io.File
import java.io.IOException

/** Where the update of the IDE is, from the release found to the new IDE running. One for the whole process. */
sealed interface UpdateState {
    /** Nothing started: the release found, if any, is only offered. */
    data object Idle : UpdateState

    /** The release being worked on, pinned: a later check does not change it. */
    sealed interface Pinned : UpdateState {
        val release: AvailableRelease
    }

    /** [done] of [total] bytes received; [total] is -1 when nobody knows it. */
    data class Downloading(override val release: AvailableRelease, val done: Long, val total: Long) : Pinned

    /** Downloaded and verified, being unpacked next to the install. */
    data class Staging(override val release: AvailableRelease) : Pinned

    /** Staged: the next restart installs it. */
    data class Ready(override val release: AvailableRelease) : Pinned

    /** The windows are closing; the last one to close starts the installer and the IDE exits. */
    data class Restarting(override val release: AvailableRelease) : Pinned

    /** Something failed, with [message] for the user; [Retry][AppUpdater.retry] starts again. */
    data class Failed(override val release: AvailableRelease, val message: String) : Pinned
}

/**
 * Downloads, verifies and stages a release, then installs it at the restart of the IDE. Every step happens while the
 * IDE runs and can fail visibly ([UpdateState.Failed]); the install itself is only swapped by a detached script
 * once the last window has closed ([launchInstaller]), never under a running IDE.
 *
 * Detection of the install, the downloads folder, the source of the files and the installer are parameters, so that
 * the tests use a fake install in a temporary directory, a loopback server and a recorder.
 */
@Stable
class AppUpdater(
    private val environment: InstallEnvironment = InstallEnvironment.current(),
    private val updatesDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor/updates"),
    downloadBase: () -> String = UpdateDownloads::defaultBase,
    downloader: VerifiedDownload = VerifiedDownload(),
    private val installer: (SwapJob) -> Unit = { UpdateSwap.start(it) },
    private val processId: () -> Long = { ProcessHandle.current().pid() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = UpdateDownloads(updatesDirectory, downloadBase, downloader)
    private var work: Job? = null

    // Counts the starts and the cancellations: a job whose number is old can no longer change the state.
    private var generation = 0

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    /** Whether the windows are asked to close so that the update installs. */
    val restarting: Boolean get() = state is UpdateState.Restarting

    /** How [release] is installed: by the IDE itself, or by the user from the release page. */
    fun planFor(release: AvailableRelease): UpdatePlan = UpdatePlanner.plan(environment, release)

    /** Downloads, verifies and stages [release]; nothing happens when an update is already under way. */
    @Synchronized
    fun start(release: AvailableRelease) {
        val busy = state is UpdateState.Pinned && state !is UpdateState.Failed
        if (busy) return
        val plan = planFor(release) as? UpdatePlan.InApp ?: return
        state = UpdateState.Downloading(release, 0, plan.asset.size)
        generation++
        val mine = generation
        work = scope.launch { run(release, plan, mine) }
    }

    /** Stops the download and deletes what was received; back to the offer. */
    @Synchronized
    fun cancel() {
        if (state !is UpdateState.Downloading) return
        work?.cancel()
        generation++
        state = UpdateState.Idle
    }

    /** Starts again after a failure; a file already downloaded and verified is not downloaded again. */
    fun retry() {
        val failed = state as? UpdateState.Failed ?: return
        start(failed.release)
    }

    /** Forgets a failure: the release is offered again. */
    @Synchronized
    fun dismissFailure() {
        if (state is UpdateState.Failed) state = UpdateState.Idle
    }

    /** Asks the windows to close so that the staged update installs. */
    @Synchronized
    fun requestRestart() {
        val ready = state as? UpdateState.Ready ?: return
        state = UpdateState.Restarting(ready.release)
    }

    /** A window did not close (the user kept it open): nothing is installed, the update waits for another restart. */
    @Synchronized
    fun restartAbandoned() {
        val restarting = state as? UpdateState.Restarting ?: return
        state = UpdateState.Ready(restarting.release)
    }

    /**
     * Starts the installer when a restart was requested, as the last window closes: the caller exits right after.
     * Returns whether it was started.
     */
    @Synchronized
    fun launchInstaller(): Boolean {
        val restarting = state as? UpdateState.Restarting
        val plan = restarting?.let { planFor(it.release) } as? UpdatePlan.InApp ?: return false
        val job = SwapJob.forTarget(plan.target, processId(), updatesDirectory)
        return try {
            installer(job)
            true
        } catch (failure: IOException) {
            System.err.println("Update not installed: ${failure.message}")
            false
        }
    }

    /** Stops what runs; the downloads folder keeps what was verified. */
    fun close() {
        scope.cancel()
    }

    private suspend fun run(release: AvailableRelease, plan: UpdatePlan.InApp, mine: Int) {
        try {
            withContext(Dispatchers.IO) { downloads.deleteOtherVersions(release) }
            val archive =
                downloads.fetch(
                    release,
                    plan.asset,
                ) { done, total -> advance(mine) { progressed(it, release, done, total) } }
            advance(mine) { UpdateState.Staging(release) }
            withContext(Dispatchers.IO) { UpdateStaging.stage(plan.target, archive) }
            advance(mine) { UpdateState.Ready(release) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // A cancellation surfaces as an I/O error of the interrupted transfer: it is not a failure.
            currentCoroutineContext().ensureActive()
            advance(mine) { UpdateState.Failed(release, messageOf(failure)) }
        }
    }

    // Only the job that was last started, and not cancelled since, moves the state.
    @Synchronized
    private fun advance(mine: Int, next: (UpdateState) -> UpdateState) {
        if (mine == generation) state = next(state)
    }
}

private fun messageOf(failure: Exception): String = failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()

// The size of a download is the one of the response, else the one the release announced for the asset.
private fun progressed(current: UpdateState, release: AvailableRelease, done: Long, total: Long): UpdateState {
    if (current !is UpdateState.Downloading || current.release != release) return current
    val known = if (total > 0) total else current.total
    return UpdateState.Downloading(release, done, known)
}
