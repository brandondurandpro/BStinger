package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.syntax.Declaration

/** A declaration of the project found by its language servers, offered by the Goto Symbol popup. */
data class SymbolMatch(
    val entry: FileEntry,
    val relativePath: String,
    val declaration: Declaration,
    val score: Int,
    val matchedIndices: List<Int>,
)
