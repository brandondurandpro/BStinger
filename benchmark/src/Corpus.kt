package org.example.kotlineditor.benchmark

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.rpc.content
import org.example.kotlineditor.rpc.diffLines
import org.example.kotlineditor.rpc.entries
import org.example.kotlineditor.rpc.entry
import org.example.kotlineditor.rpc.entryOrNull
import org.example.kotlineditor.rpc.gitStatus
import org.example.kotlineditor.rpc.matches
import org.example.kotlineditor.rpc.session
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.symbols
import org.example.kotlineditor.rpc.WireWriter
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.Structure
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** One source file loaded as the server sees it: absolute path, decoded text and split lines. */
class SourceFile(
    val path: Path,
    val entry: FileEntry,
    val lines: List<String>,
    val separator: String,
) {
    /** Body of the Read response for this file, exactly as [content] encodes it. */
    fun readBody(): ByteArray {
        val writer = WireWriter()
        writer.content(FileContent.Text(lines, separator))
        return writer.toByteArray()
    }

    /** The Read response as a code payload: the kind a language dictionary is trained for. */
    fun payload(): Payload = Payload(entry.name, readBody(), PayloadKind.CODE)
}

/** A named body, as one frame would carry it. */
class Payload(
    val name: String,
    val body: ByteArray,
    val kind: PayloadKind = PayloadKind.METADATA,
)

/** Directories never walked, like the server's indexer. */
val IGNORED_DIRECTORIES =
    setOf(".git", ".hg", ".svn", ".idea", ".gradle", ".kotlin", "build", "out", "node_modules", "target")

/** Loads every file with [extension] under [root], sorted by path, skipping ignored directories and huge files. */
fun loadSources(root: Path, extension: String, maxBytes: Long = 1_000_000): List<SourceFile> {
    val result = ArrayList<SourceFile>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() in IGNORED_DIRECTORIES) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() <= maxBytes && file.fileName.toString().endsWith(".$extension")) {
                    runCatching { readSource(file) }.onSuccess { result += it }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return result.sortedBy { it.path }
}

/** Splits a sorted corpus: every fifth file is held out for testing. */
fun split(files: List<SourceFile>): Pair<List<SourceFile>, List<SourceFile>> {
    val train = ArrayList<SourceFile>()
    val test = ArrayList<SourceFile>()
    files.forEachIndexed { index, file ->
        if (index % 5 == 4) test += file else train += file
    }
    return train to test
}

private fun readSource(path: Path): SourceFile {
    val text = String(Files.readAllBytes(path), Charsets.UTF_8)
    val (lines, separator) = splitLines(text)
    val absolute = path.toAbsolutePath().normalize()
    return SourceFile(absolute, FileEntry(absolute.toString(), path.fileName.toString(), false), lines, separator)
}

/** Same splitting as the server's TextFiles.splitLines. */
private fun splitLines(text: String): Pair<List<String>, String> {
    val lines = ArrayList<String>(text.length / 32 + 1)
    var crlf = false
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            val cr = i > start && text[i - 1] == '\r'
            crlf = crlf || cr
            lines += text.substring(start, if (cr) i - 1 else i)
            start = i + 1
        }
    }
    lines += text.substring(start)
    return lines to if (crlf) "\r\n" else "\n"
}

/** Bodies of small requests and responses, all below the current 16 KB threshold. */
fun smallPayloads(files: List<SourceFile>, query: String): List<Payload> {
    val file = files.first()
    val root = file.entry
    val writer = WireWriter()
    writer.session(ProjectSession(files.take(5).map { SessionFile(it.entry.path, 0, 0) }, file.entry.path))
    val empty = WireWriter()
    empty.content(FileContent.Text(emptyList(), "\n"))
    return listOf(
        Payload("Read request", WireWriter().entry(file.entry).toByteArray()),
        Payload("WalkFiles request", WireWriter().entry(root).int(200_000).toByteArray()),
        Payload("FindInFiles request", WireWriter().entry(root).string(query).bool(false).int(1000).toByteArray()),
        Payload("FileAt request", WireWriter().string(file.entry.path).toByteArray()),
        Payload("GitBranch request", WireWriter().string(file.entry.path).toByteArray()),
        Payload("Empty Read response", empty.toByteArray()),
        Payload("Entry response", WireWriter().entryOrNull(file.entry).toByteArray()),
        Payload("Branch response", WireWriter().string("main").toByteArray()),
        Payload("Recent projects response", WireWriter().strings(files.take(10).map { it.entry.path }).toByteArray()),
        Payload("Session response", writer.toByteArray()),
    )
}

/** Bodies of the project-wide queries: the largest payloads after file reads. */
fun projectPayloads(files: List<SourceFile>, query: String, includeSymbols: Boolean): List<Payload> {
    val payloads = ArrayList<Payload>()
    payloads += Payload("WalkFiles response", WireWriter().entries(files.map { it.entry }).toByteArray())
    payloads += Payload("ListDirectory response", WireWriter().entries(files.take(20).map { it.entry }).toByteArray())
    payloads += Payload("FindInFiles response", matchesBody(files, query, 500))
    if (includeSymbols) payloads += Payload("Symbols response", symbolsBody(files))
    payloads += Payload("GitStatus response", gitStatusBody(files))
    payloads += Payload("GitDiff response", diffBody(files.first()))
    payloads += Payload("Shell event", shellBody(files.first()))
    return payloads
}

/** A realistic session: opening a project, reading files, searching, and a few shell events. */
fun sessionPayloads(files: List<SourceFile>, query: String, includeSymbols: Boolean): List<Payload> {
    val reads = files.sortedByDescending { it.lines.size }.take(10).map { it.payload() }
    val shells = List(10) { index -> Payload("Shell event ${index + 1}", shellBody(files[index % files.size])) }
    return smallPayloads(files, query) + reads + projectPayloads(files, query, includeSymbols) + shells
}

/**
 * A 200,000-line file, to exercise the path where the current protocol does compress. Lines are shuffled so the
 * file does not repeat one 500 KB block verbatim: that would make zstd's large window look artificially good.
 */
fun largePayload(files: List<SourceFile>, lineCount: Int = 200_000): Payload {
    val pool = files.flatMap { it.lines }
    val random = kotlin.random.Random(42)
    val lines = ArrayList<String>(lineCount)
    while (lines.size < lineCount) {
        lines.addAll(pool.shuffled(random).take(lineCount - lines.size))
    }
    val writer = WireWriter()
    writer.content(FileContent.Text(lines, "\n"))
    return Payload("Read 200k lines", writer.toByteArray(), PayloadKind.CODE)
}

private fun matchesBody(files: List<SourceFile>, query: String, limit: Int): ByteArray {
    val matches = ArrayList<TextMatch>()
    loop@ for (file in files) {
        for ((index, line) in file.lines.withIndex()) {
            val column = line.indexOf(query)
            if (column >= 0) {
                matches += TextMatch(file.entry, index, column, line.take(500))
                if (matches.size >= limit) break@loop
            }
        }
    }
    return WireWriter().matches(matches).toByteArray()
}

private fun symbolsBody(files: List<SourceFile>): ByteArray {
    val symbols = ArrayList<FileSymbol>()
    for (file in files) {
        val declarations = Structure.outline(file.lines, Language.Kotlin, Structure.blockCommentStates(file.lines))
        declarations.mapTo(symbols) { FileSymbol(file.entry, it) }
    }
    return WireWriter().symbols(symbols).toByteArray()
}

private fun gitStatusBody(files: List<SourceFile>): ByteArray =
    WireWriter().gitStatus(files.take(50).associate { it.entry.path to GitFileStatus.Modified }).toByteArray()

private fun diffBody(file: SourceFile): ByteArray {
    val lines =
        file.lines.take(200).mapIndexed { index, text ->
            when {
                index % 11 == 0 -> DiffLine(DiffKind.Added, -1, index + 1, text)
                index % 17 == 0 -> DiffLine(DiffKind.Removed, index + 1, -1, text)
                index % 13 == 0 -> DiffLine(DiffKind.Hunk, -1, -1, "@@ -${index + 1},3 +${index + 1},4 @@")
                else -> DiffLine(DiffKind.Context, index + 1, index + 1, text)
            }
        }
    return WireWriter().diffLines(lines).toByteArray()
}

private fun shellBody(file: SourceFile): ByteArray =
    WireWriter().long(1).bool(false).strings(file.lines.take(20)).toByteArray()
