package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
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

    /** Searches [BATCH] files at a time in parallel, and sends the matches of each batch of files as one update. */
    override fun findInFiles(root: FileEntry, query: SearchQuery, limit: Int): Flow<SearchUpdate> =
        flow {
            var found = 0
            var truncated = false
            val batches =
                if (query.text.isEmpty() || limit <= 0) {
                    emptyList<List<FileEntry>>().iterator()
                } else {
                    walkFiles(root).chunked(BATCH).iterator()
                }
            while (found < limit && batches.hasNext()) {
                val batch = batches.next()
                val all =
                    coroutineScope {
                        batch.map { async(Dispatchers.IO) { matches(it, query) } }.awaitAll().flatten()
                    }
                val hits = all.take(limit - found)
                truncated = hits.size < all.size
                found += hits.size
                if (hits.isNotEmpty()) emit(SearchUpdate.Found(hits))
            }
            emit(SearchUpdate.Finished(SearchEngine.BuiltIn, complete = !truncated && !batches.hasNext()))
        }

    override suspend fun symbols(root: FileEntry): List<FileSymbol> {
        val sources = walkFiles(root).filter { Language.fromFileName(it.name) in OUTLINED }
        return sources.chunked(BATCH).flatMap { batch ->
            coroutineScope { batch.map { async(Dispatchers.IO) { outline(it) } }.awaitAll().flatten() }
        }
    }

    private fun matches(entry: FileEntry, query: SearchQuery): List<TextMatch> {
        val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
        return content.lines.withIndex().mapNotNull { (index, line) ->
            val column = line.indexOf(query.text, ignoreCase = query.ignoreCase)
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
