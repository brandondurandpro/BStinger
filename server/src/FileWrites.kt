package org.example.kotlineditor.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.UserDefinedFileAttributeView
import kotlin.io.path.name

// Windows refuses to replace a file another process has open without FILE_SHARE_DELETE (a virus scanner, an
// indexer, a backup tool), usually for a few milliseconds: retry for about two seconds before giving up.
private const val REPLACE_ATTEMPTS = 40
private const val REPLACE_RETRY_MS = 50L

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Writes [bytes] as the content of [path], changing nothing else of the file: through a symbolic link, the file it
 * points to; its permissions, owner, group, links and attributes stay.
 *
 * The bytes go to a temp file moved over the file, so that a crash never leaves it half written, when that move keeps
 * everything; otherwise (other hard links, another owner, a directory the server cannot write, a group it cannot
 * give) they are written in place, as the move would lose what makes the file what it is. A read-only file is not
 * written.
 */
internal suspend fun writeFile(path: Path, bytes: ByteArray) {
    val exists = Files.exists(path)
    val target = if (exists) path.toRealPath() else path
    val readOnly = exists && !Files.isWritable(target)
    if (readOnly) throw AccessDeniedException(target.toString(), null, "The file is read-only")
    val replaced = exists && movable(target) && replaceKeepingAll(target, bytes)
    if (!replaced) Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
}

/** [writeFile] for the callers that are not coroutines (the agents' requests), creating the missing directories. */
internal fun writeFileNow(path: Path, bytes: ByteArray) {
    path.parent?.let { Files.createDirectories(it) }
    runBlocking { writeFile(path, bytes) }
}

// Writes through a temp file moved over [target], if the temp file can have all of [target]; false when it cannot.
private suspend fun replaceKeepingAll(target: Path, bytes: ByteArray): Boolean {
    val temp = Files.createTempFile(target.parent, ".${target.name}", ".tmp")
    try {
        Files.write(temp, bytes)
        val copied = copyAttributes(target, temp)
        if (copied) replace(temp, target)
        return copied
    } finally {
        Files.deleteIfExists(temp)
    }
}

// Whether a file moved over [target] can be what [target] is: its only link, owned by the server's user, in a
// directory the server can write.
private fun movable(target: Path): Boolean {
    val directoryWritable = Files.isWritable(target.parent)
    val views = target.fileSystem.supportedFileAttributeViews()
    if (!directoryWritable || "unix" !in views) return directoryWritable
    val links = Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
    val owned = Files.getOwner(target).name == System.getProperty("user.name")
    return links == 1 && owned
}

// Gives [temp] the permissions, group, user attributes, DOS attributes and ACL of [target]; false when one cannot be
// given.
private fun copyAttributes(target: Path, temp: Path): Boolean {
    val posix = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
    val copied = if (posix != null) copyPosix(posix, temp) else copyWindows(target, temp)
    return copied && copyUserAttributes(target, temp)
}

private fun copyPosix(source: PosixFileAttributeView, temp: Path): Boolean {
    val attributes = source.readAttributes()
    val view = Files.getFileAttributeView(temp, PosixFileAttributeView::class.java)
    view.setPermissions(attributes.permissions())
    return runCatching { view.setGroup(attributes.group()) }.isSuccess
}

// Windows: the hidden, system and archive flags, and the access rules given to the file itself (a new file only
// inherits those of its directory).
private fun copyWindows(target: Path, temp: Path): Boolean {
    val dos = Files.getFileAttributeView(target, DosFileAttributeView::class.java)
    if (dos != null) copyDos(dos, temp)
    val acl = Files.getFileAttributeView(target, AclFileAttributeView::class.java) ?: return true
    return runCatching { Files.getFileAttributeView(temp, AclFileAttributeView::class.java).acl = acl.acl }.isSuccess
}

// The user extended attributes (`user.*` on Linux, all of them on macOS); false when one cannot be written.
private fun copyUserAttributes(target: Path, temp: Path): Boolean {
    val source = Files.getFileAttributeView(target, UserDefinedFileAttributeView::class.java)
    val names = runCatching { source?.list() }.getOrNull().orEmpty()
    if (source == null || names.isEmpty()) return true
    val copy = Files.getFileAttributeView(temp, UserDefinedFileAttributeView::class.java)
    return runCatching {
        for (name in names) {
            val value = ByteBuffer.allocate(source.size(name))
            source.read(name, value)
            value.flip()
            copy.write(name, value)
        }
    }.isSuccess
}

private fun copyDos(source: DosFileAttributeView, temp: Path) {
    val attributes = source.readAttributes()
    val view = Files.getFileAttributeView(temp, DosFileAttributeView::class.java) ?: return
    view.setHidden(attributes.isHidden)
    view.setSystem(attributes.isSystem)
    view.setArchive(attributes.isArchive)
}

private suspend fun replace(source: Path, target: Path) {
    var attempt = 1
    while (true) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: AccessDeniedException) {
            if (!WINDOWS || attempt == REPLACE_ATTEMPTS) throw e
            attempt++
            delay(REPLACE_RETRY_MS)
        }
    }
}
