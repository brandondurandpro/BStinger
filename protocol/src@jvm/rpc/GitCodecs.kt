package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitFileStatus

// Codecs of Git data.

/** Writes a Git status map. */
fun WireWriter.gitStatus(map: Map<String, GitFileStatus>): WireWriter =
    int(map.size).also {
        map.forEach { (path, status) -> string(path).byte(status.ordinal) }
    }

/** Reads a Git status map. */
fun WireReader.gitStatus(): Map<String, GitFileStatus> {
    val count = int()
    val map = HashMap<String, GitFileStatus>(count * 2)
    repeat(count) { map[string()] = GitFileStatus.entries[byte()] }
    return map
}

/** Writes the lines of a diff. */
fun WireWriter.diffLines(lines: List<DiffLine>): WireWriter =
    int(lines.size).also {
        lines.forEach { byte(it.kind.ordinal).int(it.oldLine).int(it.newLine).string(it.text) }
    }

/** Reads the lines of a diff. */
fun WireReader.diffLines(): List<DiffLine> = List(int()) { DiffLine(DiffKind.entries[byte()], int(), int(), string()) }
