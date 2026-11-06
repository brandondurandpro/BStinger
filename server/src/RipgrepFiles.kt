package org.example.kotlineditor.server

import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepMatch
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import java.io.IOException
import java.nio.file.Paths
import kotlin.time.Duration

/** Every [FileEntry] the engine makes out of a path ripgrep named: absolute in the tree, a plain file. */
internal fun fileEntryOfRipgrep(path: String): FileEntry {
    val file = Paths.get(path)
    val name = file.fileName?.toString() ?: path
    return FileEntry(file.toString(), name, false)
}

internal fun RipgrepMatch.toTextMatch(preview: Int): TextMatch =
    TextMatch(fileEntryOfRipgrep(path), line, ranges.firstOrNull()?.first ?: 0, text.take(preview))

/**
 * The file lists of ripgrep (`rg --files`, for Go to File): absolute [FileEntry]s sorted by path, so that the
 * limit cuts a stable list and the index does not change shape from one walk to the next.
 */
internal class RipgrepFiles(
    private val maxFileSize: Long,
    private val timeout: Duration,
    private val onStart: (ProcessHandle) -> Unit,
    private val binary: suspend () -> RipgrepBinary?,
) {
    /** The files ripgrep lists, or null when ripgrep failed: the built-in walk takes over. */
    suspend fun entries(root: FileEntry, limit: Int): List<FileEntry>? {
        val executable = binary() ?: return null
        return try {
            val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
            val paths = ArrayList<String>()
            // On timeout, the files listed so far are the answer.
            withTimeoutOrNull(timeout) { runner.files(Paths.get(root.path)).collect(paths::add) }
            // ripgrep lists files in no particular order: sorting keeps the list stable from one walk to the
            // next, and the limit then cuts the same list every time (the built-in walk truncates its own).
            paths.sorted().take(limit).map(::fileEntryOfRipgrep)
        } catch (_: IOException) {
            null
        }
    }
}
