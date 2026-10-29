package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.pathName

// Codecs of files and their content, shared by client and server so both sides always agree.

private const val TEXT = 0
private const val BINARY = 1
private const val TOO_LARGE = 2
private const val ERROR = 3

/** Writes an entry: only the path and the kind travel, the name is derived from the path. */
fun WireWriter.entry(entry: FileEntry): WireWriter = string(entry.path).bool(entry.isDirectory)

/** Reads an entry written by [WireWriter.entry]. */
fun WireReader.entry(): FileEntry {
    val path = string()
    return FileEntry(path, pathName(path), bool())
}

/** Writes an optional entry. */
fun WireWriter.entryOrNull(entry: FileEntry?): WireWriter = bool(entry != null).also { if (entry != null) entry(entry) }

/** Reads an optional entry. */
fun WireReader.entryOrNull(): FileEntry? = if (bool()) entry() else null

/** Writes a list of entries. */
fun WireWriter.entries(list: List<FileEntry>): WireWriter = int(list.size).also { list.forEach { entry(it) } }

/** Reads a list of entries. */
fun WireReader.entries(): List<FileEntry> = List(int()) { entry() }

/** Writes a list of strings. */
fun WireWriter.strings(list: List<String>): WireWriter = int(list.size).also { list.forEach { string(it) } }

/** Reads a list of strings. */
fun WireReader.strings(): List<String> = List(int()) { string() }

/** Writes a file content; text is sent as lines, already split by the server. */
fun WireWriter.content(content: FileContent) {
    when (content) {
        is FileContent.Text -> byte(TEXT).string(content.lineSeparator).strings(content.lines)
        FileContent.Binary -> byte(BINARY)
        is FileContent.TooLarge -> byte(TOO_LARGE).long(content.size)
        is FileContent.Error -> byte(ERROR).string(content.message)
    }
}

/** Reads a file content. */
fun WireReader.content(): FileContent =
    when (byte()) {
        TEXT -> {
            val separator = string()
            FileContent.Text(strings(), separator)
        }

        BINARY -> {
            FileContent.Binary
        }

        TOO_LARGE -> {
            FileContent.TooLarge(long())
        }

        else -> {
            FileContent.Error(string())
        }
    }
