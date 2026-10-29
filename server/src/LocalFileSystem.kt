package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val DIRECTORIES_FIRST =
    compareBy<FileEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** [ProjectFileSystem] on the server's disk (NIO, on the IO dispatcher). */
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
        withContext(Dispatchers.IO) { TextFiles.read(Paths.get(file.path), maxFileSize) }

    /** Synchronous read, for searches already running on IO threads. */
    fun readNow(file: FileEntry): FileContent = TextFiles.read(Paths.get(file.path), maxFileSize)

    override suspend fun fileAt(path: String): FileEntry? =
        withContext(Dispatchers.IO) {
            val p = runCatching { Paths.get(path) }.getOrNull()
            if (p != null && Files.isRegularFile(p)) FileEntry(p.toString(), p.name, false) else null
        }

    override suspend fun write(file: FileEntry, text: String) {
        withContext(Dispatchers.IO) {
            // Write to a sibling temp file then move it, so a crash never leaves a half-written file.
            val target = Paths.get(file.path)
            val temp = Files.createTempFile(target.parent, ".${target.name}", ".tmp")
            try {
                Files.writeString(temp, text)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
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
            require(newName.isNotBlank() && '/' !in newName && '\\' !in newName) { "Invalid name: $newName" }
            val source = Paths.get(entry.path)
            val target = Files.move(source, source.resolveSibling(newName))
            FileEntry(target.toString(), target.name, entry.isDirectory)
        }

    override suspend fun delete(entry: FileEntry) {
        withContext(Dispatchers.IO) {
            val path = Paths.get(entry.path)
            if (Files.isDirectory(path)) path.toFile().deleteRecursively() else Files.deleteIfExists(path)
        }
    }

    // Resolves `name` inside `directory`, refusing anything that would escape it (`../`).
    private fun child(directory: FileEntry, name: String): Path {
        val base = Paths.get(directory.path).normalize()
        val target = base.resolve(name.trim()).normalize()
        require(name.isNotBlank() && target.startsWith(base) && target != base) { "Invalid name: $name" }
        return target
    }
}
