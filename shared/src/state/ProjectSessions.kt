package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import java.io.IOException

/** Reopens the files of the previous session, with their caret and the active tab. */
fun ProjectState.restoreSession(session: ProjectSession) {
    scope.launch {
        val opened =
            session.openFiles.mapNotNull { file ->
                val entry =
                    try {
                        services.fileSystem.fileAt(file.path)
                    } catch (expected: IOException) {
                        null // unreachable now: the file is simply not reopened
                    }
                entry?.let { file.path to editor.openAt(it, TextPos(file.line, file.column)) }
            }
        opened.firstOrNull { it.first == session.activeFile }?.let { editor.activate(it.second) }
    }
}

fun ProjectState.session(): ProjectSession =
    ProjectSession(
        editor.allTabs.filterNot { it.isDiff }.distinctBy { it.entry.path }.map { tab ->
            val caret = tab.document?.caret ?: tab.pendingSelection?.first ?: TextPos(0, 0)
            SessionFile(tab.entry.path, caret.line, caret.column)
        },
        editor.activeTab
            ?.takeUnless { it.isDiff }
            ?.entry
            ?.path,
    )
