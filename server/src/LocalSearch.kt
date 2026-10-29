package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.Structure
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/** Project-wide queries, run in parallel batches next to the files: only results travel to the client. */
class LocalSearch(private val files: LocalFileSystem) : ProjectSearch {
    override suspend fun walkFiles(root: FileEntry, limit: Int): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<FileEntry>()
            Files.walkFileTree(Paths.get(root.path), Collector(root.path, limit, result))
            result
        }

    override suspend fun findInFiles(root: FileEntry, query: String, ignoreCase: Boolean, limit: Int): List<TextMatch> {
        val hits = ArrayList<TextMatch>()
        val batches =
            if (query.isEmpty()) {
                emptyList<List<FileEntry>>().iterator()
            } else {
                walkFiles(root).chunked(BATCH).iterator()
            }
        while (hits.size < limit && batches.hasNext()) {
            val batch = batches.next()
            hits +=
                coroutineScope {
                    batch.map { async(Dispatchers.IO) { matches(it, query, ignoreCase) } }.awaitAll().flatten()
                }
        }
        return hits.take(limit)
    }

    override suspend fun symbols(root: FileEntry): List<FileSymbol> {
        val sources = walkFiles(root).filter { Language.fromFileName(it.name) in OUTLINED }
        return sources.chunked(BATCH).flatMap { batch ->
            coroutineScope { batch.map { async(Dispatchers.IO) { outline(it) } }.awaitAll().flatten() }
        }
    }

    private fun matches(entry: FileEntry, query: String, ignoreCase: Boolean): List<TextMatch> {
        val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
        return content.lines.withIndex().mapNotNull { (index, line) ->
            val column = line.indexOf(query, ignoreCase = ignoreCase)
            if (column < 0) null else TextMatch(entry, index, column, line.take(MAX_LINE_PREVIEW))
        }
    }

    private fun outline(entry: FileEntry): List<FileSymbol> {
        val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
        val language = Language.fromFileName(entry.name)
        val declarations = Structure.outline(content.lines, language, Structure.blockCommentStates(content.lines))
        return declarations.map { FileSymbol(entry, it) }
    }

    private class Collector(
        private val root: String,
        private val limit: Int,
        private val result: MutableList<FileEntry>,
    ) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir.toString() != root &&
                dir.name in IGNORED_DIRECTORIES
            ) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) result += FileEntry(file.toString(), file.name, false)
            return if (result.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        // Unreadable entries (permissions, races with deletions) are skipped, like IntelliJ's indexer does.
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    }

    private companion object {
        const val BATCH = 64
        const val MAX_LINE_PREVIEW = 500
        val OUTLINED = setOf(Language.Kotlin, Language.Java)
    }
}
