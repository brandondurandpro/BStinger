package org.example.kotlineditor.state

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.GitChangeKind
import org.example.kotlineditor.model.GitLineChange

/**
 * Turns the unified diff of an open file into the gutter marks of the editor: added lines, modified lines (a
 * removal paired with an addition) and one deletion mark per removed block.
 */
fun lineChangesOf(lines: List<DiffLine>): List<GitLineChange> {
    val changes = ArrayList<GitLineChange>()
    var index = 0
    var lastNewLine = 0
    while (index < lines.size) {
        val line = lines[index]
        when (line.kind) {
            DiffKind.Hunk -> {
                index++
            }

            DiffKind.Context -> {
                lastNewLine = maxOf(lastNewLine, line.newLine)
                index++
            }

            DiffKind.Removed, DiffKind.Added -> {
                val removed = ArrayList<DiffLine>()
                val added = ArrayList<DiffLine>()
                while (index < lines.size && lines[index].kind.isChange()) {
                    val changed = lines[index]
                    if (changed.kind == DiffKind.Removed) removed += changed else added += changed
                    lastNewLine = maxOf(lastNewLine, changed.newLine)
                    index++
                }
                val paired = minOf(removed.size, added.size)
                changes += added.take(paired).map { GitLineChange(it.newLine - 1, GitChangeKind.Modified) }
                changes += added.drop(paired).map { GitLineChange(it.newLine - 1, GitChangeKind.Added) }
                if (removed.size > added.size) {
                    changes += GitLineChange(deletionAnchor(lines, index, added, lastNewLine), GitChangeKind.Deleted)
                }
            }
        }
    }
    return changes.sortedBy { it.line }
}

private fun DiffKind.isChange(): Boolean = this == DiffKind.Removed || this == DiffKind.Added

// 0-based line before which the removed block sat in the new file (the document length when it was at the end).
private fun deletionAnchor(lines: List<DiffLine>, index: Int, added: List<DiffLine>, lastNewLine: Int): Int {
    val firstAdded = added.firstOrNull()?.newLine
    val nextContext =
        lines
            .drop(index)
            .firstOrNull { it.kind == DiffKind.Context }
            ?.newLine
    return (firstAdded ?: nextContext ?: lastNewLine + 1) - 1
}
