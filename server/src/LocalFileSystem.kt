package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileChangedException
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileVersion
import org.example.kotlineditor.model.ProjectFileSystem
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val DIRECTORIES_FIRST =
    compareBy<FileEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** [ProjectFileSystem] on the server's disk (NIO, on the IO dispatcher). */
@Suppress("TooManyFunctions")
class LocalFileSystem(private val maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE) : ProjectFileSystem {
    override suspend fun resolveDirectory(path: String): FileEntry? =
        withContext(Dispatchers.IO) {
            val p = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull()
            if (p == null ||
                !Files.isDirectory(p)
            ) {
                null
            } else {
                FileEntry(p.toString(), p.fileName?.toString() ?: p.toString(), true)
            }
        }

    override suspend fun list(directory: FileEntry): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val children =
                Files.newDirectoryStream(Paths.get(directory.path)).use { stream ->
                    stream.map { FileEntry(it.toString(), it.name, it.isDirectory()) }
                }
            children.sortedWith(DIRECTORIES_FIRST)
        }

    override suspend fun read(file: FileEntry): FileContent =
        withContext(Dispatchers.IO) { TextFiles.readWithVersion(Paths.get(file.path), maxFileSize) }

    /** Synchronous read, for searches already running on IO threads. */
    fun readNow(file: FileEntry): FileContent = TextFiles.read(Paths.get(file.path), maxFileSize)

    override suspend fun fileAt(path: String): FileEntry? =
        withContext(Dispatchers.IO) {
            val p = runCatching { Paths.get(path) }.getOrNull()
            if (p != null && Files.isRegularFile(p)) FileEntry(p.toString(), p.name, false) else null
        }

    // One write of a file at a time.
    override suspend fun write(file: FileEntry, text: String, expected: FileVersion?): FileVersion? =
        withContext(Dispatchers.IO) {
            val path = Paths.get(file.path)
            withPathLock(path) { writeText(path, text, expected) }
        }

    /**
     * [write] asked by [writer] (a client's session) with its request [order] (its ids increase): one write of a file
     * at a time, and a write sent before another of the same file by the same writer, which reaches the file after
     * it, is dropped, the file keeping the newer text. A dropped write checks nothing and returns no version
     * (`null`): it did not run; an executed one checks against [expected] and returns the version written.
     */
    suspend fun write(
        file: FileEntry,
        text: String,
        writer: String,
        order: Long,
        expected: FileVersion? = null,
    ): FileVersion? =
        withContext(Dispatchers.IO) {
            val path = Paths.get(file.path)
            writeInOrder(path, writer, order) { writeText(path, text, expected) }
        }

    // Reads what the file holds, encodes [text] with the charset those bytes show, checks [expected] against the
    // version the disk holds (skipped when there is nothing to check: no file yet, or no stat), writes through
    // [writeFile], then returns the version of what it wrote.
    private suspend fun writeText(path: Path, text: String, expected: FileVersion?): FileVersion? {
        val existing = if (Files.exists(path)) Files.readAllBytes(path) else null
        val encoded = TextFiles.encode(text, existing)
        if (expected != null) {
            val current = versionOf(path, existing)
            if (current != null && isStale(current, expected, existing, encoded)) {
                throw FileChangedException()
            }
        }
        writeFile(path, encoded)
        return versionOf(path, encoded)
    }

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry =
        withContext(Dispatchers.IO) {
            val target = child(directory, name)
            Files.createDirectories(target.parent)
            Files.createFile(target)
            FileEntry(target.toString(), target.name, false)
        }

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry =
        withContext(Dispatchers.IO) {
            val target = child(directory, name)
            if (Files.exists(target)) throw FileAlreadyExistsException(target.toString())
            Files.createDirectories(target)
            FileEntry(target.toString(), target.name, true)
        }

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry =
        withContext(Dispatchers.IO) {
            require('/' !in newName && '\\' !in newName) { "Invalid name: $newName" }
            requireValidName(newName)
            val source = Paths.get(entry.path)
            val target = moveRenaming(source, source.resolveSibling(newName))
            FileEntry(target.toString(), target.name, entry.isDirectory)
        }

    override suspend fun delete(entry: FileEntry) {
        withContext(Dispatchers.IO) {
            deleteTree(Paths.get(entry.path))
        }
    }

    // Resolves `name` inside `directory`, refusing anything that would escape it (`../`).
    private fun child(directory: FileEntry, name: String): Path {
        requireValidName(name.trim())
        val base = Paths.get(directory.path).normalize()
        val target = base.resolve(name.trim()).normalize()
        require(name.isNotBlank() && target.startsWith(base) && target != base) { "Invalid name: $name" }
        return target
    }

    // Whether the save is stale: the version the disk holds ([current]) is not the one [expected] was read from,
    // and the bytes to write are not the ones the disk holds (which would be a replayed save, that may pass).
    private fun isStale(
        current: FileVersion,
        expected: FileVersion,
        existing: ByteArray?,
        encoded: ByteArray,
    ): Boolean {
        val alreadyWritten = existing != null && encoded.contentEquals(existing)
        return current != expected && !alreadyWritten
    }
}

/**
 * Deletes [root] and, when it is a directory, everything in it. A link is deleted itself, never what it points to: a
 * symbolic link (to a file or a directory, inside the project or not) and, on Windows, a junction or another reparse
 * point. Nothing happens when [root] is already gone.
 */
internal fun deleteTree(root: Path) {
    val attributes =
        try {
            Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
    if (attributes.isWalked()) Files.walkFileTree(root, TreeDeletion) else Files.deleteIfExists(root)
}

// A real directory, whose content is deleted with it; not a link (a symbolic link is not a directory without
// following it) nor a junction (a directory that is also "other": a reparse point).
private fun BasicFileAttributes.isWalked(): Boolean = isDirectory && !isOther

// The walk of [deleteTree], which never follows a link: those are given to visitFile, or met as reparse points.
private object TreeDeletion : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (attrs.isWalked()) return FileVisitResult.CONTINUE
        Files.delete(dir)
        return FileVisitResult.SKIP_SUBTREE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        Files.delete(file)
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
        if (exc != null) throw exc
        Files.delete(dir)
        return FileVisitResult.CONTINUE
    }
}
