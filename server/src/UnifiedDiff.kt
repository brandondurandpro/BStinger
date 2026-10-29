package org.example.kotlineditor.server

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

/** Parses the output of `git diff` for one file into numbered lines; file headers are skipped. */
internal class UnifiedDiff {
    private val lines = ArrayList<DiffLine>()
    private var oldLine = 0
    private var newLine = 0
    private var inHunk = false

    fun parse(output: List<String>): List<DiffLine> {
        output.forEach(::accept)
        return lines
    }

    private fun accept(line: String) {
        val hunk = HUNK_HEADER.matchEntire(line)
        if (hunk != null) {
            oldLine = hunk.groupValues[1].toInt()
            newLine = hunk.groupValues[2].toInt()
            inHunk = true
            lines += DiffLine(DiffKind.Hunk, -1, -1, line)
        } else if (inHunk) {
            body(line)?.let { lines += it }
        }
    }

    // A line inside a hunk; `\ No newline at end of file` and anything unexpected are ignored.
    private fun body(line: String): DiffLine? {
        val text = line.drop(1)
        return when (line.firstOrNull()) {
            '+' -> DiffLine(DiffKind.Added, -1, newLine++, text)
            '-' -> DiffLine(DiffKind.Removed, oldLine++, -1, text)
            ' ' -> DiffLine(DiffKind.Context, oldLine++, newLine++, text)
            else -> null
        }
    }

    companion object {
        /** A file Git does not know yet: every line is an addition. */
        fun newFile(content: List<String>): List<DiffLine> =
            listOf(DiffLine(DiffKind.Hunk, -1, -1, "@@ -0,0 +1,${content.size} @@")) +
                content.mapIndexed { index, text -> DiffLine(DiffKind.Added, -1, index + 1, text) }
    }
}
