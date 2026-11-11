package org.example.kotlineditor.server

import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
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

    /** [file] (or directory) was created. */
    fun created(file: FileEntry) {
        if (!file.isDirectory) notify(listOf(file.path to DiskChange.Created))
        operated(FileOperation.Create, OperatedFile(file.path, directory = file.isDirectory))
    }

    /** The files under [entry] (or [entry] itself), before it is renamed or deleted. */
    fun filesOf(entry: FileEntry): List<String> =
        when {
            !services.observed -> emptyList()
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
        operated(FileOperation.Rename, OperatedFile(old.path, renamed.path, old.isDirectory))
    }

    /** [entry] was deleted, with [before] (from [filesOf]). */
    fun deleted(before: List<String>, entry: FileEntry) {
        notify(before.map { it to DiskChange.Deleted })
        operated(FileOperation.Delete, OperatedFile(entry.path, directory = entry.isDirectory))
    }

    private fun operated(operation: FileOperation, file: OperatedFile) {
        services.filesOperated(operation, listOf(file))
    }

    private fun notify(changes: List<Pair<String, DiskChange>>) {
        services.diskChanged(changes)
    }
}
