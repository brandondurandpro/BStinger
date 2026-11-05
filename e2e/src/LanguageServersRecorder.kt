package org.example.kotlineditor.e2e

import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.TextChange
import java.util.concurrent.CopyOnWriteArrayList

/** A request the UI sent to the language servers of one server, with the project or file it names. */
data class LanguageServerCall(val method: String, val target: String)

/**
 * Sits between the UI and the language servers of the app's own server (the requests still go to it) and records
 * what the UI declares and mirrors there: tests can then check that a server the IDE left is told nothing more.
 */
class LanguageServersRecorder(private val servers: LanguageServers) : LanguageServers by servers {
    private val recorded = CopyOnWriteArrayList<LanguageServerCall>()

    /** The declarations and document changes sent, oldest first. */
    val calls: List<LanguageServerCall> get() = recorded.toList()

    override suspend fun use(project: String, files: List<String>): List<String> {
        recorded += LanguageServerCall("use", project)
        return servers.use(project, files)
    }

    override suspend fun openDocument(project: String, path: String, version: Int, text: String) {
        recorded += LanguageServerCall("openDocument", path)
        servers.openDocument(project, path, version, text)
    }

    override suspend fun changeDocument(path: String, version: Int, changes: List<TextChange>) {
        recorded += LanguageServerCall("changeDocument", path)
        servers.changeDocument(path, version, changes)
    }

    override suspend fun closeDocument(path: String) {
        recorded += LanguageServerCall("closeDocument", path)
        servers.closeDocument(path)
    }
}
