package org.example.kotlineditor.lsp.provision

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The language server the user chose for each language, saved on the server machine (the binaries run there, and
 * one process serves every session of a project): a global default per language, which a project may override.
 *
 * Stored as a properties file (`php=phpactor`, `php@/home/me/shop=intelephense`), written atomically. A missing or
 * unreadable file means that no choice was made yet: the choice panel is shown again, never an error.
 */
class LanguageServerChoiceStore(
    private val file: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", FILE_NAME),
) {
    private val lock = Any()

    /** The server chosen for [language] in [projectRoot] (its override, else the global default), or null. */
    fun choice(language: String, projectRoot: String? = null): String? {
        val saved = synchronized(lock) { load() }
        return projectRoot?.let { saved.getProperty(key(language, it)) } ?: saved.getProperty(language)
    }

    /** Saves [serverId] for [language]: for [projectRoot] only when given, otherwise as the global default. */
    fun choose(language: String, serverId: String, projectRoot: String? = null) {
        synchronized(lock) {
            val saved = load()
            saved.setProperty(if (projectRoot == null) language else key(language, projectRoot), serverId)
            save(saved)
        }
    }

    /** Removes the override of [language] in [projectRoot]: the global default applies again. */
    fun clearProjectChoice(language: String, projectRoot: String) {
        synchronized(lock) {
            val saved = load()
            if (saved.remove(key(language, projectRoot)) != null) save(saved)
        }
    }

    private fun key(language: String, projectRoot: String) = "$language@$projectRoot"

    private fun load(): Properties =
        Properties().also { properties ->
            try {
                if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(properties::load)
            } catch (_: IOException) {
                properties.clear()
            } catch (_: IllegalArgumentException) {
                // A malformed escape sequence: the file is corrupted, treated as no choice yet.
                properties.clear()
            }
        }

    private fun save(properties: Properties) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val part = Files.createTempFile(file.toAbsolutePath().parent, "language-servers-", ".part")
        try {
            Files.newBufferedWriter(part).use { properties.store(it, "Language server chosen for each language") }
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(part)
        }
    }

    private companion object {
        const val FILE_NAME = "language-servers.properties"
    }
}
