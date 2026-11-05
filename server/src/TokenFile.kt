package org.example.kotlineditor.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * A secret passed through a file rather than on a command line, where every user of the machine reads it (`ps`,
 * `/proc/<pid>/cmdline`). The server (`--token-file`) and the IDE (`--token-file`) both read it this way.
 */
object TokenFile {
    private const val OWNER_ONLY = "rw-------"

    /** The token in [file], trimmed; throws [IOException] when it cannot be read or is empty. */
    fun read(file: File): String {
        val text =
            try {
                file.readText()
            } catch (e: IOException) {
                throw IOException("Cannot read the token file $file: ${e.message}", e)
            }
        val token = text.trim()
        if (token.isEmpty()) throw IOException("The token file $file is empty")
        return token
    }

    /** Writes [token] to [file], readable by its owner only when the file system has POSIX permissions. */
    fun write(file: Path, token: String) {
        Files.deleteIfExists(file)
        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY))
        val created =
            try {
                Files.createFile(file, ownerOnly)
            } catch (_: UnsupportedOperationException) {
                // Windows has no POSIX permissions; its ACLs already keep the user's directories private.
                Files.createFile(file)
            }
        Files.writeString(created, token)
    }
}
