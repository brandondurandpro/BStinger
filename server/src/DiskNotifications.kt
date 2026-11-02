package org.example.kotlineditor.server

import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.model.FileEntry
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.asSequence

// Most files of a directory the language servers are told about when it is renamed or deleted.
private const val MAX_NOTIFIED_FILES = 10_000

/**
 * Tells the language servers what a file operation of the IDE changed on disk, once the language servers run:
 * they would not see it otherwise (they index the files that are not open).
 */
internal class DiskNotifications(private val services: LocalServices) {
    /** [file] was written. */
    fun written(file: FileEntry) = notify(listOf(file.path to DiskChange.Changed))

    /** [file] was created. */
    fun created(file: FileEntry) = notify(listOf(file.path to DiskChange.Created))

    /** The files under [entry] (or [entry] itself), before it is renamed or deleted. */
    fun filesOf(entry: FileEntry): List<String> =
        when {
            !services.languageServersStarted -> emptyList()
            !entry.isDirectory -> listOf(entry.path)
            else -> filesUnder(entry)
        }

    private fun filesUnder(entry: FileEntry): List<String> =
        try {
            Files.walk(Paths.get(entry.path)).use { paths ->
                paths
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .take(MAX_NOTIFIED_FILES)
                    .map { it.toString() }
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: UncheckedIOException) {
            emptyList()
        }

    /** [before] (from [filesOf]) moved from [old] to [renamed]. */
    fun renamed(before: List<String>, old: FileEntry, renamed: FileEntry) {
        val deleted = before.map { it to DiskChange.Deleted }
        val created = before.map { renamed.path + it.removePrefix(old.path) to DiskChange.Created }
        notify(deleted + created)
    }

    /** [before] (from [filesOf]) were deleted. */
    fun deleted(before: List<String>) = notify(before.map { it to DiskChange.Deleted })

    private fun notify(changes: List<Pair<String, DiskChange>>) {
        if (services.languageServersStarted && changes.isNotEmpty()) services.languageServers.diskChanged(changes)
    }
}
