package org.example.kotlineditor.state

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.model.DeployItem
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.model.relativeToRoot
import java.io.IOException
import java.nio.file.Paths

// Changes that follow each other (a build, a save of several files) are deployed together.
private const val AUTO_DELAY_MS = 400L
private const val AUTO_MAX_WAIT_MS = 3_000L

// A file the program writing it still holds (Windows) makes a run fail: the same files are tried again a few times.
private const val AUTO_MAX_RETRIES = 3
private const val NANOS_PER_MS = 1_000_000L
private const val GIT = ".git"

/**
 * The automatic deployments: the files of the project that change on disk (by the IDE or any other program), under
 * the folders of a deployment in automatic mode, are deployed alone, once the changes stop for a moment.
 */
internal class AutoDeploy(private val deployments: DeploymentsState, private val project: ProjectState) {
    private val pending = HashMap<String, MutableSet<PendingFile>>()
    private val jobs = HashMap<String, Job>()
    private val firstQueued = HashMap<String, Long>()
    private val retries = HashMap<String, Int>()

    /** [paths] changed: queues them for each automatic deployment whose folders hold them. */
    fun changed(paths: List<String>) {
        for (profile in deployments.profiles.filter { it.auto }) {
            val matching = paths.flatMap { filesOf(profile, it) }
            if (matching.isNotEmpty()) queue(profile.name, matching)
        }
    }

    // The files of [profile] that [path] is, once mapped to where they land; none when it is not under its folders.
    private fun filesOf(profile: DeployProfile, path: String): List<PendingFile> {
        val relative = relativeToRoot(path, project.root.path)
        val inGit = relative == GIT || relative.startsWith("$GIT/")
        val skipped = inGit || isInLocalDestination(project.root.path, profile.destination, path)
        if (skipped) return emptyList()
        val folders = profile.folders.ifEmpty { listOf(DeployFolder("", "")) }
        return folders.mapNotNull { folder -> landingUnder(folder, relative)?.let { PendingFile(path, it) } }
    }

    private fun queue(name: String, files: List<PendingFile>) {
        pending.getOrPut(name) { LinkedHashSet() }.addAll(files)
        val now = System.nanoTime()
        val first = firstQueued.getOrPut(name) { now }
        val waitedMs = (now - first) / NANOS_PER_MS
        // Changes that never stop (a watcher, a log) must not hold the deployment back for ever: it leaves at most
        // [AUTO_MAX_WAIT_MS] after the first change, and the next ones go with the next run.
        val waiting = jobs[name]?.isActive == true
        if (waitedMs >= AUTO_MAX_WAIT_MS && waiting) return
        jobs[name]?.cancel()
        jobs[name] =
            project.scope.launch {
                delay(AUTO_DELAY_MS)
                firstQueued.remove(name)
                flush(name)
            }
    }

    private suspend fun flush(name: String) {
        val files = pending.remove(name).orEmpty()
        val profile = deployments.profiles.firstOrNull { it.name == name && it.auto }
        if (profile == null || files.isEmpty()) return
        // The previous run is not over: these files go with the next one.
        if (deployments.statuses[name] == DeployStatus.Running) {
            queue(name, files.toList())
            return
        }
        val fs = project.services.fileSystem
        val items = files.mapNotNull { file -> existingEntry(fs, file.path)?.let { DeployItem(it, file.target) } }
        val destination = DeployDestination.parse(profile.destination)
        if (items.isNotEmpty() && destination != null) {
            deployments.deploy(profile, items, destination, true)
            retryIfFailed(name, files)
        }
    }

    private fun retryIfFailed(name: String, files: Set<PendingFile>) {
        val failed = deployments.statuses[name] is DeployStatus.Failed
        val attempts = retries[name] ?: 0
        if (failed && attempts < AUTO_MAX_RETRIES) {
            retries[name] = attempts + 1
            queue(name, files.toList())
        } else {
            retries.remove(name)
        }
    }
}

// A file that changed, and where it lands under the destination.
private data class PendingFile(val path: String, val target: String)

// Where the file at [relative] lands if it is under the folder (or is it); null when it is elsewhere.
private fun landingUnder(folder: DeployFolder, relative: String): String? {
    val source = folder.source
    val inside = source.isEmpty() || relative == source || relative.startsWith("$source/")
    if (!inside) return null
    val rest = if (source.isEmpty()) relative else relative.removePrefix(source).trimStart('/')
    return listOf(folder.target, rest).filter { it.isNotEmpty() }.joinToString("/")
}

// A deployment into the project itself (`dist`) must not deploy what it writes, again and again.
private fun isInLocalDestination(root: String, destination: String, path: String): Boolean {
    val parsed = DeployDestination.parse(destination)
    if (parsed == null || parsed.isRemote) return false
    return runCatching {
        val folder = Paths.get(root).resolve(parsed.path).normalize()
        Paths.get(path).normalize().startsWith(folder)
    }.getOrDefault(false)
}

// The file or directory at [path] if it is still there: a file deleted since it changed has nothing to deploy.
private suspend fun existingEntry(fs: ProjectFileSystem, path: String): FileEntry? =
    try {
        fs.fileAt(path) ?: fs.resolveDirectory(path)
    } catch (_: IOException) {
        null
    }
