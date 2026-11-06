package org.example.kotlineditor.server

import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileVersion
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * The version the file [path] holds (through a symbolic link, of its target) when [bytes] are its whole content,
 * `null` when it cannot be taken (no file, or the disk refuses the stat: the caller then skips the check).
 *
 * The stat and the hash describe the same bytes only while nothing writes the file between them: the caller
 * reads first (or knows the bytes written), so the rare race answers too old, not too new.
 */
@Suppress("ReturnCount") // each of these steps replaces the version with "not taken"
fun versionOf(path: Path, bytes: ByteArray?): FileVersion? {
    if (bytes == null) return null
    val target = realTargetOf(path) ?: return null
    val attributes = statOf(target) ?: return null
    return FileVersion(
        mtimeMs = attributes.lastModifiedTime().toMillis(),
        size = attributes.size(),
        fileKey = fileKeyOf(target),
        sha256 = sha256Hex(bytes),
    )
}

private fun statOf(target: Path): BasicFileAttributes? =
    try {
        Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

// The file at [path], links followed, `null` when there is none (a link whose target is missing included).
private fun realTargetOf(path: Path): Path? =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

// What the disk identifies the file by (the Unix inode, as a string), `null` where NIO has no such view
// (Windows, most non-POSIX disks) or refuses to (a mount without the attribute): the hash then decides alone.
private fun fileKeyOf(target: Path): String? =
    try {
        val inode = Files.getAttribute(target, "unix:ino") as? Long ?: return null
        inode.toString()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

// The SHA-256 of [bytes], lower-case hex, like DiskSnapshot hashes the files of a project.
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** [content] carrying the version the file [path] held when [bytes] were read; unchanged when there is none. */
internal fun FileContent.withVersion(path: Path, bytes: ByteArray): FileContent {
    val version = versionOf(path, bytes)
    return when (this) {
        is FileContent.Text -> FileContent.Text(lines, lineSeparator, version)
        is FileContent.Binary -> FileContent.Binary(version)
        else -> this
    }
}
