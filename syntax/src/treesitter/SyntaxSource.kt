package org.example.kotlineditor.syntax.treesitter

/** The text a [SyntaxSession] highlights: an editor document, whose [version] is snapshot state. */
interface SyntaxSource {
    /** Changes on every edit; read in a snapshot so that the session reparses after each change. */
    val version: Int

    /** A copy of the lines, safe to read off the UI thread. */
    fun snapshotLines(): List<String>
}
