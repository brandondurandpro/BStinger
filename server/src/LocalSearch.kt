package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.server.ripgrep.searchDirectories
import org.example.kotlineditor.syntax.Language
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
        walkFiles(root, limit, includeIgnored = false)

    override suspend fun findInFiles(
        root: FileEntry,
        query: String,
        ignoreCase: Boolean,
        limit: Int,
    ): List<TextMatch> = findInFiles(root, FindOptions(text = query, ignoreCase = ignoreCase), limit)

    override suspend fun findInFiles(root: FileEntry, options: FindOptions, limit: Int): List<TextMatch> {
        val matches = ArrayList<TextMatch>()
        matchBatches(root, options, limit).collect { matches += it }
        return matches.take(limit)
    }

    /**
     * The matches of [options], one batch per bunch of files: they arrive while the later files are read, so a
     * search can show them as they are found. An empty query matches nothing, and the batches stop once [limit]
     * lines were found in total.
     */
    fun matchBatches(root: FileEntry, options: FindOptions, limit: Int): Flow<List<TextMatch>> =
        flow { batches(root, options, limit) }

    // The batches of one search, as they are found: nothing for an empty query, then the batch of each bunch of
    // files, up to the [limit] lines.
    private suspend fun FlowCollector<List<TextMatch>>.batches(root: FileEntry, options: FindOptions, limit: Int) {
        if (options.text.isEmpty()) return // an empty query matches nothing, like the plain search did
        val matcher = matcherOf(options)
        var emitted = 0
        for (filesOfBatch in searchFiles(root, options).chunked(BATCH)) {
            val remaining = limit - emitted
            if (remaining <= 0) break
            val batchMatches =
                coroutineScope {
                    filesOfBatch
                        .map { async(Dispatchers.IO) { matchesIn(files, it, matcher) } }
                        .awaitAll()
                        .flatten()
                }
            emitted += batchMatches.size
            emit(batchMatches.take(remaining))
        }
    }

    override suspend fun symbols(root: FileEntry): List<FileSymbol> {
        val sources = walkFiles(root).filter { Language.fromFileName(it.name) in OUTLINED }
        return sources.chunked(BATCH).flatMap { batch ->
            coroutineScope { batch.map { async(Dispatchers.IO) { symbolsIn(files, it) } }.awaitAll().flatten() }
        }
    }

    // Every regular file under [root], within [limit], walking into ignored directories when [includeIgnored].
    private suspend fun walkFiles(root: FileEntry, limit: Int, includeIgnored: Boolean): List<FileEntry> {
        val collected = ArrayList<FileEntry>()
        val collector = Collector(root.path, limit, collected, includeIgnored)
        withContext(Dispatchers.IO) { Files.walkFileTree(Paths.get(root.path), collector) }
        return collected
    }

    // The files a Find in Files looks into: the whole project, or its [options.scopes], and only the files of
    // [options.filePattern] when one is set (like ripgrep: a pattern without `/` applies to any directory).
    private suspend fun searchFiles(root: FileEntry, options: FindOptions): List<FileEntry> {
        val everyFile = walkFiles(root, ProjectSearch.DEFAULT_WALK_LIMIT, options.includeIgnored)
        val scopes = options.scopes.takeIf { it.isNotEmpty() }?.let { searchDirectories(Paths.get(root.path), it) }
        val globs = FileGlobs.parse(options.filePattern)
        if (scopes == null && globs == null) return everyFile
        return everyFile.filter { entry -> withinScope(entry, scopes) && selectedBy(entry, root, globs) }
    }

    // Whether [entry] is in one of [scopes]: no scope, or the file under one (a race with a deletion leaves it out).
    private fun withinScope(entry: FileEntry, scopes: List<Path>?): Boolean =
        scopes == null ||
            runCatching {
                val path = Paths.get(entry.path).toAbsolutePath().normalize()
                scopes.any { path.startsWith(it) }
            }.getOrDefault(false)

    // Whether the patterns of [globs] select [entry]: its name, or its path from the root when a pattern has a slash.
    private fun selectedBy(entry: FileEntry, root: FileEntry, globs: FileGlobs?): Boolean =
        globs == null || globs.selects(pathName(entry.path), relativeToRoot(entry.path, root.path))

    private class Collector(
        private val root: String,
        private val limit: Int,
        private val result: MutableList<FileEntry>,
        /** Whether ignored directories (`.git`, build output) are walked into as well. */
        private val includeIgnored: Boolean,
    ) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // Ignored directories (`.git`, build output) are skipped when not walking into them (the root is kept).
            val ignoredDirectory = !includeIgnored && dir.name in IGNORED_DIRECTORIES
            return if (ignoredDirectory && dir.toString() != root) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }
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
        val OUTLINED = setOf(Language.Kotlin, Language.Java)
    }
}
