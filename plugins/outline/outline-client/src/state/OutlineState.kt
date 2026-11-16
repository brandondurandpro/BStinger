package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.Structure
import java.io.IOException

/** The outline of the active document, as the Outline window shows it. */
@Stable
class OutlineState {
    /** Declarations of the outlined file, in file order. */
    var outline: List<Declaration> by mutableStateOf(emptyList())
        private set

    /** Path of the file [outline] came from, relative to the project root; null when nothing is outlined. */
    var sourcePath: String? by mutableStateOf(null)
        private set

    /** Whether [outline] is the one of the file at [relativePath] (the panel shows it as loading otherwise). */
    fun isCurrent(relativePath: String?): Boolean = relativePath != null && sourcePath == relativePath

    /**
     * Recomputes the outline of the active [tab] and its [document]: the declarations found locally (Kotlin, Java),
     * or the ones of the language servers when the language has no local outline; none when no text document is
     * active (a diff tab, an empty workspace).
     */
    suspend fun update(host: ProjectHost, tab: EditorTab?, document: Document?) {
        if (tab == null || document == null) {
            outline = emptyList()
            sourcePath = null
            return
        }
        val lines = document.lines.toList()
        val commentStates = document.commentStates
        val local = withContext(Dispatchers.Default) { Structure.outline(lines, document.language, commentStates) }
        outline = if (local.isEmpty()) fromServers(host, document) else local
        sourcePath = tab.relativePath
    }

    /** The declarations the language servers give for [document]; none while the server is out of reach. */
    private suspend fun fromServers(host: ProjectHost, document: Document): List<Declaration> =
        try {
            host.serverOutline(document).orEmpty()
        } catch (expected: IOException) {
            emptyList() // server unreachable: nothing to list
        }
}

/** Key of the [OutlineState] a project keeps for the Outline window. */
val OUTLINE_SERVICE = ServiceKey<OutlineState>("outline")
