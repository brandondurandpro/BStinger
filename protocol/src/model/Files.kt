package org.example.kotlineditor.model

import java.io.IOException

/** A file or directory of the opened project. Paths are absolute and platform-specific (on the server). */
data class FileEntry(
    /** Absolute path on the server. */
    val path: String,
    /** Last path segment, shown in the UI. */
    val name: String,
    /** Whether this is a directory. */
    val isDirectory: Boolean,
    /**
     * What a tree shows for a directory standing for a chain of directories each holding only the next, such as
     * `src/main/java` (its [path] is then the last of them); `null` for any other entry. See [ProjectFileSystem.listFolded].
     */
    val label: String? = null,
)

/**
 * The version of a file on the server's disk: what every read and write carries, and what a save checks.
 *
 * [mtimeMs] and [size] come from a stat of the file really written (behind a symbolic link, of its target),
 * [fileKey] is what the disk identifies it with (its inode, when the system reveals it, `null` otherwise: on
 * Windows, most mounts), and [sha256] is the hash of its bytes, so a change of line endings or encoding alone is
 * still seen while a mere touch is not. Two versions of the same file are of the same content when they are
 * equal ([equals]).
 */
data class FileVersion(
    /** Last modification time in milliseconds since the epoch, only used to spare the hash (never trusted alone). */
    val mtimeMs: Long,
    /** Size of the file in bytes. */
    val size: Long,
    /** Key of the file on the disk (the Unix inode), `null` where the system does not reveal one. */
    val fileKey: String? = null,
    /** The SHA-256 of the bytes of the file, lower-case hex. */
    val sha256: String,
)

/**
 * The message answering a save asked with a [FileVersion] the disk no longer holds: nothing was written, the
 * bytes on it belong to another program's edit. Server and client share it, so both name the same failure.
 */
const val FILE_CHANGED_MESSAGE = "The file changed on disk since it was read: reload or keep your changes"

/**
 * A save refused because the file changed on the disk since the version the client read: [FILE_CHANGED_MESSAGE],
 * as an exception, so the client tells it from every other save failure (it shows the conflict banner, not an
 * error).
 */
class FileChangedException(message: String = FILE_CHANGED_MESSAGE) : IOException(message)

/** Result of reading a file from disk. */
sealed interface FileContent {
    /** The version of the file on the disk when it could be read, `null` otherwise (too large, error). */
    val version: FileVersion?
        get() = null

    /** A text file, split into [lines] without terminators; [lineSeparator] is restored on save. */
    data class Text(
        /** Lines of the file. */
        val lines: List<String>,
        /** Line separator used by the file. */
        val lineSeparator: String = "\n",
        /** Version of the file the lines were read from. */
        override val version: FileVersion? = null,
    ) : FileContent

    /** A file that is not text (contains NUL bytes). */
    data class Binary(
        /** Version of the file that was only checked, never decoded. */
        override val version: FileVersion? = null,
    ) : FileContent

    /** A file too large to be opened, of [size] bytes. */
    data class TooLarge(
        /** Size in bytes. */
        val size: Long,
    ) : FileContent

    /** A file that could not be read. */
    data class Error(
        /** Why it could not be read. */
        val message: String,
    ) : FileContent
}

/** One line matching a Find in Files query. */
interface ProjectFileSystem {
    /** Returns the entry for [path] if it is an existing directory, `null` otherwise. */
    suspend fun resolveDirectory(path: String): FileEntry?

    /** Children of [directory], directories first, then case-insensitive by name. */
    suspend fun list(directory: FileEntry): List<FileEntry>

    /**
     * [list], where a directory holding only one directory, itself holding only one directory and so on, is
     * returned as the last of the chain, [FileEntry.label] telling the names (`src/main/java`), as compact
     * middle packages. The server decides, as the client of a remote server does not see the disk.
     */
    suspend fun listFolded(directory: FileEntry): List<FileEntry> = list(directory)

    /** Content of [file]. */
    suspend fun read(file: FileEntry): FileContent

    /** The regular file at [path], or `null` if there is none (used to restore sessions). */
    suspend fun fileAt(path: String): FileEntry?

    /**
     * Replaces the content of [file] with [text]; when [expected] (the version the caller read) is not `null`, the
     * write fails instead of silently overwriting the file if it changed on the disk since. Returns the version of
     * the file once written, `null` when the write was superseded (a newer save of the same file already ran).
     */
    suspend fun write(file: FileEntry, text: String, expected: FileVersion? = null): FileVersion?

    /** Creates an empty file; [name] may contain `/` to create intermediate directories. */
    suspend fun createFile(directory: FileEntry, name: String): FileEntry

    /** Creates the directory [name] in [directory]. */
    suspend fun createDirectory(directory: FileEntry, name: String): FileEntry

    /** Renames [entry] to [newName] in the same directory. */
    suspend fun rename(entry: FileEntry, newName: String): FileEntry

    /** Deletes a file, or a directory with everything it contains. */
    suspend fun delete(entry: FileEntry)
}
