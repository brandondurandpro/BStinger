package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry

// Keyboard navigation in the project tree, and project-relative paths.

fun FileTreeState.selectedIndex(): Int = rows.indexOfFirst { it.entry.path == selectedPath }

fun FileTreeState.moveSelection(delta: Int) {
    val current = rows
    if (current.isNotEmpty()) {
        selectedPath =
            current[
                (selectedIndex() + delta).coerceIn(0, current.lastIndex),
            ].entry.path
    }
}

/** Right arrow: expands a collapsed directory, or moves to its first child. */
fun FileTreeState.expandOrDescend() {
    val index = selectedIndex()
    val row = rows.getOrNull(index)
    when {
        row == null || !row.entry.isDirectory -> Unit
        !row.expanded -> expand(row.entry)
        else -> rows.getOrNull(index + 1)?.takeIf { it.depth > row.depth }?.let { selectedPath = it.entry.path }
    }
}

/** Left arrow: collapses an expanded directory, or moves to the parent. */
fun FileTreeState.collapseOrAscend() {
    val current = rows
    val index = selectedIndex()
    val row = current.getOrNull(index) ?: return
    if (row.entry.isDirectory && row.expanded) {
        collapse(row.entry)
    } else {
        val parent = (index - 1 downTo 0).firstOrNull { current[it].depth < row.depth }
        parent?.let { selectedPath = current[it].entry.path }
    }
}

fun FileTreeState.relativePath(entry: FileEntry): String = relativePath(entry.path)

fun FileTreeState.relativePath(path: String): String =
    if (path == root.path) root.name else path.removePrefix(root.path).trimStart('/', '\\').replace('\\', '/')
