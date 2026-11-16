package org.example.kotlineditor.todo.server

import kotlinx.serialization.json.JsonElement
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.example.kotlineditor.model.relativeToRoot
import org.example.kotlineditor.server.TextFiles
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/** One TODO or FIXME marker of a line of a file of the project. */
data class TodoMarker(
    /** Path of the file, relative to the project root, with `/` separators. */
    val path: String,
    /** Line of the marker in its file, counted from 1. */
    val line: Int,
    /** Text of the line that holds the marker. */
    val text: String,
)

private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
private const val MAX_FILE_BYTES = 512L * 1024

// The lines that hold a marker; the text is matched as written, like IntelliJ does with its TODO patterns.
private val MARKERS = listOf("TODO:", "FIXME:")

/**
 * The TODO and FIXME markers of the files under [root] (an absolute path): lines that hold `TODO:` or `FIXME:`
 * (case-sensitive), in the order the walk finds them. The walk skips the ignored directories (`.git`, build
 * output...), the files that are not text and the big ones, and stops once it has read [maxTotalBytes] of them,
 * so a scan costs a few megabytes whatever the project.
 */
internal fun todoScan(root: String, maxTotalBytes: Long = MAX_TOTAL_BYTES): List<TodoMarker> {
    val collector = Collector(Paths.get(root), maxTotalBytes)
    Files.walkFileTree(collector.root, collector)
    return collector.found
}

private class Collector(
    /** Absolute path walked; its files are reported relative to it. */
    val root: Path,
    /** Bytes left before the scan stops early; spent as the files are read. */
    private var budget: Long,
) : SimpleFileVisitor<Path>() {
    val found = mutableListOf<TodoMarker>()

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        val ignored = dir.name in IGNORED_DIRECTORIES && dir != root
        return if (ignored) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (budget <= 0) return FileVisitResult.TERMINATE
        budget -= attrs.size()
        val content = TextFiles.read(file, MAX_FILE_BYTES)
        if (content is FileContent.Text) found += markersIn(file, content.lines)
        return FileVisitResult.CONTINUE
    }

    // Unreadable entries (permissions, races with deletions) are skipped, like the search does.
    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

    private fun markersIn(file: Path, lines: List<String>): List<TodoMarker> {
        val relative = relativeToRoot(file.toString(), root.toString())
        val markers = mutableListOf<TodoMarker>()
        for ((index, line) in lines.withIndex()) {
            if (holdsMarker(line)) {
                markers += TodoMarker(relative, index + 1, line.trim())
            }
        }
        return markers
    }

    private fun holdsMarker(line: String): Boolean = MARKERS.any { marker -> line.contains(marker) }
}
