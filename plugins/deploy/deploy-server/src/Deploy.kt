package org.example.kotlineditor.deploy.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployItem
import org.example.kotlineditor.model.DeployResult
import org.example.kotlineditor.model.Deployer
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.remote.SshConfig
import org.example.kotlineditor.remote.SshConfigHost
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal const val VCS_DIRECTORY = ".git"
private val DRIVE = Regex("^[A-Za-z]:")

/** A source of a deployment, and the path under the destination where it lands (cleaned, see [cleanTarget]). */
internal class PlannedItem(val path: Path, val target: String)

/** What a deployment takes: the items, checked, and what they hold. */
internal class DeployPlan(val root: Path, val items: List<PlannedItem>, val result: DeployResult)

/**
 * [Deployer] run on the server: a folder of its disk is written with the file API, a folder of another machine
 * with `tar` piped into `ssh` (the same prerequisites as the remote server: OpenSSH on this machine, a POSIX `sh`
 * and `tar` on the host, key or agent authentication, host keys already known). [ssh] is the command that runs
 * OpenSSH, replaceable for tests.
 */
class LocalDeployer(private val ssh: List<String> = listOf("ssh")) : Deployer {
    override suspend fun deploy(
        root: FileEntry,
        items: List<DeployItem>,
        destination: DeployDestination,
    ): DeployResult =
        withContext(Dispatchers.IO) {
            val plan = planDeployment(root, items)
            if (destination.host == null) {
                copyToLocalFolder(plan, destination.path)
            } else {
                sendOverSsh(plan, destination.host.orEmpty(), destination.path, ssh)
            }
            plan.result
        }

    override suspend fun sshHosts(): List<SshConfigHost> = withContext(Dispatchers.IO) { SshConfig.load() }
}

/** The items checked and measured; fails when a source is outside the root or gone, or a target leaves the folder. */
internal fun planDeployment(root: FileEntry, items: List<DeployItem>): DeployPlan {
    require(items.isNotEmpty()) { "Nothing to deploy" }
    val base = Paths.get(root.path).toAbsolutePath().normalize()
    val planned = items.map { PlannedItem(sourceInRoot(base, it.source), cleanTarget(it.target)) }.distinct()
    var files = 0
    var bytes = 0L
    for (item in planned) {
        val walk = Measure()
        Files.walkFileTree(item.path, walk)
        files += walk.files
        bytes += walk.bytes
    }
    return DeployPlan(base, planned, DeployResult(files, bytes))
}

private fun List<PlannedItem>.distinct(): List<PlannedItem> = distinctBy { it.path to it.target }

private fun sourceInRoot(base: Path, source: FileEntry): Path {
    val path = Paths.get(source.path).toAbsolutePath().normalize()
    require(path.startsWith(base)) { "${source.path} is outside the project" }
    // Deleted by another program since the tree listed it.
    require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "${source.path} does not exist" }
    return path
}

/**
 * The path [text] names under the destination, with `/` separators and no empty or `.` segment; empty is the
 * destination itself. Fails for a path that leaves the destination (absolute, a drive, `..`).
 */
internal fun cleanTarget(text: String): String {
    val slashed = text.trim().replace('\\', '/')
    val segments = slashed.split('/').filter { it.isNotEmpty() && it != "." }
    val leaves = slashed.startsWith("/") || DRIVE.containsMatchIn(slashed) || ".." in segments
    require(!leaves) { "The path '$text' must stay inside the destination" }
    return segments.joinToString("/")
}

// Counts the files a deployment copies, links included (they are not followed), without the Git data.
private class Measure : SimpleFileVisitor<Path>() {
    var files = 0
    var bytes = 0L

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (dir.fileName?.toString() == VCS_DIRECTORY) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        files++
        bytes += attrs.size()
        return FileVisitResult.CONTINUE
    }
}
