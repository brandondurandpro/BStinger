package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.Structure
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystems
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
    fun matchBatches(root: FileEntry, options: FindOptions, limit: Int): Flow<List<TextMatch>> = flow {
        if (options.text.isEmpty()) return@flow
        val matcher = matcherOf(options)
        var emitted = 0
        for (batch in searchFiles(root, options).chunked(BATCH)) {
            val remaining = limit - emitted
            if (remaining <= 0) return@flow
            val batchMatches =
                coroutineScope { batch.map { async(Dispatchers.IO) { match(it, matcher) } }.awaitAll().flatten() }
            emitted += batchMatches.size
            emit(batchMatches.take(remaining))
        }
    }

    override suspend fun symbols(root: FileEntry): List<FileSymbol> {
        val sources = walkFiles(root).filter { Language.fromFileName(it.name) in OUTLINED }
        return sources.chunked(BATCH).flatMap { batch ->
            coroutineScope { batch.map { async(Dispatchers.IO) { outline(it) } }.awaitAll().flatten() }
        }
    }

    /** Every regular file under [root], within [limit], walking into ignored directories when [includeIgnored]. */
    private suspend fun walkFiles(root: FileEntry, limit: Int, includeIgnored: Boolean): List<FileEntry> {
        val collected = ArrayList<FileEntry>()
        val collector = Collector(root.path, limit, collected, includeIgnored)
        withContext(Dispatchers.IO) { Files.walkFileTree(Paths.get(root.path), collector) }
        return collected
    }

    // The files a Find in Files looks into: the whole project, or its [options.scope], and only the files of
    // [options.filePattern] when one is set (like ripgrep: a pattern without `/` applies to any directory).
    private suspend fun searchFiles(root: FileEntry, options: FindOptions): List<FileEntry> {
        val everyFile = walkFiles(root, ProjectSearch.DEFAULT_WALK_LIMIT, options.includeIgnored)
        val scope = options.scope?.let { Paths.get(root.path).resolve(it).toAbsolutePath().normalize() }
        val pattern = options.filePattern
        val glob = pattern?.let(::globMatcher)
        if (scope == null && glob == null) return everyFile
        val byRelativePath = pattern?.contains('/') ?: false
        return everyFile.filter { entry -> withinScope(entry, scope) && selectedBy(entry, root, glob, byRelativePath) }
    }

    // Whether [entry] is in [scope]: no scope, or the file under it (a race with a deletion leaves the file out).
    private fun withinScope(entry: FileEntry, scope: Path?): Boolean =
        scope == null ||
            runCatching { Paths.get(entry.path).toAbsolutePath().normalize().startsWith(scope) }.getOrDefault(false)

    // Whether [glob] selects [entry]: applied to its name when the pattern has no slash, to its relative path
    // otherwise.
    private fun selectedBy(
        entry: FileEntry,
        root: FileEntry,
        glob: java.nio.file.PathMatcher?,
        byRelativePath: Boolean,
    ): Boolean {
        if (glob == null) return true
        val subject =
            if (byRelativePath) Paths.get(relativeToRoot(entry.path, root.path)) else Paths.get(pathName(entry.path))
        return runCatching { glob.matches(subject) }.getOrDefault(false)
    }

    // A glob of Find in Files; null when the pattern is not a valid one (no file is selected by it).
    private fun globMatcher(glob: String): java.nio.file.PathMatcher? =
        runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull()

    private fun match(entry: FileEntry, matcher: Matcher): List<TextMatch> {
        val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
        return content.lines.withIndex().mapNotNull { (index, line) ->
            val column = matcher.columnOf(line)
            if (column < 0) null else TextMatch(entry, index, column, line.take(MAX_LINE_PREVIEW))
        }
    }

    private fun outline(entry: FileEntry): List<FileSymbol> {
        val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
        val language = Language.fromFileName(pathName(entry.path))
        val declarations = Structure.outline(content.lines, language, Structure.blockCommentStates(content.lines))
        return declarations.map { FileSymbol(entry, it) }
    }

    private class Collector(
        private val root: String,
        private val limit: Int,
        private val result: MutableList<FileEntry>,
        /** Whether ignored directories (`.git`, build output) are walked into as well. */
        private val includeIgnored: Boolean,
    ) : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (!includeIgnored && dir.toString() != root && dir.name in IGNORED_DIRECTORIES) {
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

    /** How a line is matched: the zero-based column of its first match, or a negative one for no match. */
    private sealed interface Matcher {
        fun columnOf(line: String): Int
    }

    private class LiteralMatcher(private val text: String, private val ignoreCase: Boolean, private val whole: Boolean) :
        Matcher {
        override fun columnOf(line: String): Int {
            var start = line.indexOf(text, ignoreCase = ignoreCase)
            if (!whole) return start
            // The first occurrence bounded by non-word characters on both sides (letters, digits and `_`).
            while (start >= 0) {
                if (bounded(line, start, start + text.length)) return start
                start = line.indexOf(text, startIndex = start + 1, ignoreCase = ignoreCase)
            }
            return -1
        }

        private fun bounded(line: String, from: Int, till: Int): Boolean {
            val before = line.getOrNull(from - 1)?.let(::isWordChar) ?: false
            val after = line.getOrNull(till)?.let(::isWordChar) ?: false
            return !before && !after
        }

        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    }

    private class RegexMatcher(private val regex: Regex) : Matcher {
        override fun columnOf(line: String): Int = regex.find(line)?.range?.first ?: -1
    }

    private companion object {
        const val BATCH = 64
        const val MAX_LINE_PREVIEW = 500
        val OUTLINED = setOf(Language.Kotlin, Language.Java)

        fun matcherOf(options: FindOptions): Matcher =
            when {
                options.text.isEmpty() -> EmptyMatcher
                !options.regex -> LiteralMatcher(options.text, options.ignoreCase, options.wholeWord)
                else -> RegexMatcher(regexOf(options))
            }

        // A whole-word regex is bounded by word boundaries like ripgrep's `--word-regexp` for any pattern.
        private fun regexOf(options: FindOptions): Regex {
            val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val pattern = if (options.wholeWord) "\\b(?:${options.text})\\b" else options.text
            return Regex(pattern, flags)
        }
    }

    /** A matcher of an empty query: nothing matches, and the batches are never asked for it. */
    private object EmptyMatcher : Matcher {
        override fun columnOf(line: String): Int = -1
    }
}
