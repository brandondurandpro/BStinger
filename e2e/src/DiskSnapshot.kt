package org.example.kotlineditor.e2e

import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.relativeTo
import kotlin.test.fail

// What a file is beyond its text: the tests of the features that write must not change any of it by accident.
private data class FileState(
    val digest: String,
    val permissions: String?,
    val link: String?,
    // Where the disk has them: the group, the number of hard links and the user extended attributes (name=value;
    // on Windows the alternate data streams, such as the Zone.Identifier of a downloaded file).
    val group: String? = null,
    val links: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    // Windows: the access rules of the file. Its DOS flags (hidden, system, read-only) go in [permissions].
    val acl: String? = null,
)

/**
 * The state of a project that its tests do not usually look at: the bytes, permissions and link of every file, and
 * the state of Git (HEAD, branches, index and status). Taken before a feature runs, it checks afterwards that the
 * feature changed only what it was meant to ([assertUnchanged]); a failure lists every difference.
 */
class DiskSnapshot private constructor(private val files: Map<String, FileState>, private val git: List<String>) {
    /** Fails, listing the differences, unless the project is as when [take] took this snapshot, but for [except]. */
    fun assertUnchanged(project: SampleProject, except: Set<String> = emptySet()) {
        val now = take(project)
        val paths = (files.keys + now.files.keys).filterNot { it in except }.toSortedSet()
        val differences = paths.mapNotNull { difference(it, files[it], now.files[it]) }.toMutableList()
        val gitBefore = git.filterNot { it.excepted(except) }
        val gitNow = now.git.filterNot { it.excepted(except) }
        if (gitBefore != gitNow) differences += "git: ${gitBefore.joinToString(" | ")} -> ${gitNow.joinToString(" | ")}"
        if (differences.isNotEmpty()) fail("The disk changed:\n" + differences.joinToString("\n"))
    }

    /** Takes snapshots. */
    companion object {
        /** The state of [project] now. */
        fun take(project: SampleProject): DiskSnapshot = DiskSnapshot(filesOf(project.root), gitStateOf(project))
    }
}

private fun difference(path: String, before: FileState?, after: FileState?): String? =
    when {
        before == after -> null
        before == null -> "$path: created"
        after == null -> "$path: deleted"
        before.link != after.link -> "$path: link ${before.link} -> ${after.link}"
        before.permissions != after.permissions -> "$path: permissions ${before.permissions} -> ${after.permissions}"
        before.group != after.group -> "$path: group ${before.group} -> ${after.group}"
        before.links != after.links -> "$path: hard links ${before.links} -> ${after.links}"
        before.attributes != after.attributes -> "$path: attributes ${before.attributes} -> ${after.attributes}"
        before.acl != after.acl -> "$path: access rules ${before.acl} -> ${after.acl}"
        else -> "$path: bytes changed"
    }

// Every file but those of `.git`, which [gitStateOf] reads through Git: Git creates and deletes its lock files there
// at any time.
private fun filesOf(root: Path): Map<String, FileState> {
    val files = HashMap<String, FileState>()
    val gitDirectory = root.resolve(".git")
    val visitor =
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir == gitDirectory) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                files[file.relativeTo(root).joinToString("/")] = stateOf(file)
                return FileVisitResult.CONTINUE
            }
        }
    Files.walkFileTree(root, visitor)
    return files
}

private fun stateOf(path: Path): FileState {
    val link = if (path.isSymbolicLink()) path.readSymbolicLink().toString() else null
    val bytes = if (link == null) Files.readAllBytes(path) else ByteArray(0)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val views = path.fileSystem.supportedFileAttributeViews()
    return when {
        link != null -> FileState(digest, null, link)
        "posix" in views -> posixStateOf(path, digest)
        "dos" in views -> windowsStateOf(path, digest)
        else -> FileState(digest, null, null)
    }
}

private fun posixStateOf(path: Path, digest: String): FileState {
    val posix = Files.readAttributes(path, PosixFileAttributes::class.java)
    val links = Files.getAttribute(path, "unix:nlink") as Int
    return FileState(
        digest,
        PosixFilePermissions.toString(posix.permissions()),
        null,
        posix.group().name,
        links,
        userAttributesOf(path),
    )
}

// The archive flag is left out: Windows sets it on every write, for backup tools.
private fun windowsStateOf(path: Path, digest: String): FileState {
    val dos = Files.readAttributes(path, DosFileAttributes::class.java)
    val flags =
        listOfNotNull(
            "hidden".takeIf { dos.isHidden },
            "system".takeIf { dos.isSystem },
            "read-only".takeIf { dos.isReadOnly },
        )
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.acl
    return FileState(
        digest,
        flags.joinToString(","),
        null,
        attributes = userAttributesOf(path),
        acl = acl?.joinToString(";"),
    )
}

private fun userAttributesOf(path: Path): Map<String, String> {
    val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return emptyMap()
    val names = runCatching { view.list() }.getOrDefault(emptyList())
    return names.associateWith { name ->
        val buffer = ByteBuffer.allocate(view.size(name))
        view.read(name, buffer)
        String(buffer.array())
    }
}

// HEAD, the branches, what the index holds and the status; nothing when the project is not a repository.
private fun gitStateOf(project: SampleProject): List<String> {
    val repository = Files.isDirectory(project.root.resolve(".git/objects"))
    if (!repository) return emptyList()
    val head = project.git("rev-parse", "HEAD").trim()
    val branches = project.git("for-each-ref", "--format=%(refname) %(objectname)").trim()
    val index = project.git("ls-files", "--stage").hashCode().toString()
    val porcelain = project.git("-c", "core.quotePath=false", "status", "--porcelain", "-uall")
    val status = porcelain.lines().filter { it.isNotBlank() }
    return listOf("HEAD $head", "refs $branches", "index $index") + status.map { "status $it" }
}

// A status line of one of [paths] (`status XY path`).
private fun String.excepted(paths: Set<String>): Boolean = startsWith("status ") && drop("status ".length + 3) in paths
