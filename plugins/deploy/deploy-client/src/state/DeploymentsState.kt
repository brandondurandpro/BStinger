package org.example.kotlineditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployItem
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.model.DeployResult
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.remote.SshConfigHost
import java.io.IOException

/** What a deployment of the list is doing or did. */
sealed interface DeployStatus {
    /** Copying. */
    data object Running : DeployStatus

    /** Copied: the line to show. */
    data class Done(val text: String) : DeployStatus

    /** Failed: why. */
    data class Failed(val text: String) : DeployStatus
}

/**
 * A folder of the form: the [source] as typed, and the [target] (where it lands under the destination) as typed, or
 * `null` while the user did not change it: it then follows the source, the same path.
 */
data class DraftFolder(val source: String, val target: String? = null)

/** A deployment being created or edited: the fields as typed. [original] is the name it had, `null` for a new one. */
data class DeployDraft(
    val original: String?,
    val name: String,
    // One entry per folder of the form; the blank ones are ignored.
    val folders: List<DraftFolder>,
    val destination: String,
    val auto: Boolean = false,
    val error: String? = null,
    // Whether the form asks for a machine reached over SSH (a host and a folder) rather than a folder of the disk.
    val remote: Boolean = false,
)

/**
 * The deployments saved for the project (which folders go where), kept by the server, and the popup that lists and
 * runs them. Editing is in `DeploymentDrafts.kt`, the automatic mode in [AutoDeploy].
 */
class DeploymentsState(internal val project: DeployHost) {
    private val auto = AutoDeploy(this, project)

    /** Whether the popup is shown. */
    var open by mutableStateOf(false)
        private set

    /** The saved deployments, once loaded. */
    var profiles by mutableStateOf<List<DeployProfile>>(emptyList())
        private set

    /** Whether the saved deployments are being loaded. */
    var loading by mutableStateOf(false)
        private set

    /** The deployment being created or edited, `null` while the list is shown. */
    var draft by mutableStateOf<DeployDraft?>(null)

    /** The machines of the SSH configuration of the server, offered as destinations; empty until loaded. */
    var sshHosts by mutableStateOf<List<SshConfigHost>>(emptyList())
        internal set

    /** Why the list could not be loaded or saved. */
    var error by mutableStateOf<String?>(null)
        private set

    /** What each deployment does or did, by name. */
    var statuses by mutableStateOf<Map<String, DeployStatus>>(emptyMap())
        private set

    /** Shows the popup with the saved deployments. */
    fun show() {
        open = true
        draft = null
        load()
    }

    /** Closes the popup. */
    fun close() {
        open = false
        draft = null
    }

    /** Loads the saved deployments, as the project opens too: the automatic ones must work before the popup shows. */
    fun load() {
        loading = true
        error = null
        project.scope.launch {
            try {
                profiles = project.services.deployProfiles.load(project.root.path)
            } catch (e: IOException) {
                error = e.message ?: e::class.simpleName.orEmpty()
            } finally {
                loading = false
            }
        }
    }

    /** Removes [profile] from the saved deployments. */
    fun delete(profile: DeployProfile) {
        persist(profiles.filterNot { it.name == profile.name }) {}
    }

    /** Saves a deployment that just ran, so that it can run again; one of the same name is replaced. */
    fun remember(profile: DeployProfile) {
        project.scope.launch {
            try {
                val saved = project.services.deployProfiles.load(project.root.path)
                val list = saved.filterNot { it.name == profile.name } + profile
                project.services.deployProfiles.save(project.root.path, list)
                profiles = list
            } catch (e: IOException) {
                error = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    /** Runs [profile]: its folders are copied to its destination, and its line shows how it went. */
    fun run(profile: DeployProfile) {
        if (statuses[profile.name] == DeployStatus.Running) return
        val destination = DeployDestination.parse(profile.destination)
        if (destination == null) {
            statuses = statuses + (profile.name to DeployStatus.Failed("The destination is not valid"))
            return
        }
        project.scope.launch { deploy(profile, itemsOf(project, profile), destination, automatic = false) }
    }

    /** Sends all the folders of the automatic [profile], as its changes are unknown. */
    internal suspend fun runAutomatically(profile: DeployProfile) {
        val destination = DeployDestination.parse(profile.destination) ?: return
        deploy(profile, itemsOf(project, profile), destination, automatic = true)
    }

    /** Files of the project changed on disk: the automatic deployments send those under their folders. */
    fun filesChanged(paths: List<String>) = auto.changed(paths)

    /** The watch lost track of the changes (too many at once): the automatic deployments send all their folders. */
    fun changesLost() = auto.lostTrack()

    /** Replaces the saved deployments with [list], then calls [done]; a failure is shown. */
    internal fun persist(list: List<DeployProfile>, done: () -> Unit) {
        project.scope.launch {
            try {
                project.services.deployProfiles.save(project.root.path, list)
                profiles = list
                error = null
                done()
            } catch (e: IOException) {
                val message = e.message ?: e::class.simpleName.orEmpty()
                draft = draft?.copy(error = message)
                error = message
            }
        }
    }

    /** Copies [items] for [profile] and records how it went; [automatic] says it was not asked for. */
    internal suspend fun deploy(
        profile: DeployProfile,
        items: List<DeployItem>,
        destination: DeployDestination,
        automatic: Boolean,
    ) {
        statuses = statuses + (profile.name to DeployStatus.Running)
        val status =
            try {
                val result = project.services.deployer.deploy(project.root, items, destination)
                val summary = deploySummary(result, destination)
                DeployStatus.Done(if (automatic) "Automatic: $summary" else summary)
            } catch (e: IOException) {
                DeployStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        statuses = statuses + (profile.name to status)
    }
}

private fun itemsOf(project: DeployHost, profile: DeployProfile): List<DeployItem> {
    if (profile.folders.isEmpty()) return listOf(DeployItem(project.root, ""))
    return profile.folders.map { DeployItem(entryOf(project, it.source), it.target) }
}

// The project itself for the empty path.
private fun entryOf(project: DeployHost, source: String): FileEntry =
    if (source.isEmpty()) project.root else project.entryAt(source)

/** What a finished deployment says: how many files and bytes went where. */
fun deploySummary(result: DeployResult, destination: DeployDestination): String {
    val noun = if (result.files == 1) "file" else "files"
    return "Deployed ${result.files} $noun (${formatBytes(result.bytes)}) to $destination"
}
