package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.DeclarationKind

// Codecs of project-wide data (search results, symbols, Git status, sessions).

/** Writes Find in Files results. */
fun WireWriter.matches(list: List<TextMatch>): WireWriter =
    int(list.size).also {
        list.forEach { entry(it.entry).int(it.line).int(it.column).string(it.lineText) }
    }

/** Reads Find in Files results. */
fun WireReader.matches(): List<TextMatch> = List(int()) { TextMatch(entry(), int(), int(), string()) }

/** Writes one declaration. */
fun WireWriter.declaration(d: Declaration): WireWriter =
    string(d.name)
        .byte(d.kind.ordinal)
        .int(d.line)
        .int(d.column)
        .int(d.depth)

/** Reads one declaration. */
fun WireReader.declaration(): Declaration = Declaration(string(), DeclarationKind.entries[byte()], int(), int(), int())

/** Writes the declarations of a project. */
fun WireWriter.symbols(list: List<FileSymbol>): WireWriter =
    int(list.size).also {
        list.forEach { entry(it.entry).declaration(it.declaration) }
    }

/** Reads the declarations of a project. */
fun WireReader.symbols(): List<FileSymbol> = List(int()) { FileSymbol(entry(), declaration()) }

/** Writes an optional project session. */
fun WireWriter.session(session: ProjectSession?) {
    bool(session != null)
    if (session != null) {
        string(session.activeFile)
        int(session.openFiles.size)
        session.openFiles.forEach { string(it.path).int(it.line).int(it.column) }
    }
}

/** Reads an optional project session. */
fun WireReader.session(): ProjectSession? =
    if (bool()) {
        val active = stringOrNull()
        ProjectSession(List(int()) { SessionFile(string(), int(), int()) }, active)
    } else {
        null
    }
