package org.example.kotlineditor.lsp.intelligence

import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile

private const val WORKSPACE = "workspace"

/**
 * `workspace/willCreateFiles`, `willRenameFiles` and `willDeleteFiles`: the edits the servers of a project make
 * before the IDE creates, renames or deletes files (a class renamed with its file), asked to each server whose
 * filters match the files, one after the other.
 */
internal class FileOperationRequests(
    private val requests: ServerRequests,
    private val serversOfSession: (String) -> List<ManagedServer>,
) {
    /** The edits of the servers concerned, null when no server of the project is ready. */
    suspend fun willChange(session: String, operation: FileOperation, files: List<OperatedFile>): List<FileChange>? {
        val name = "will${operation.lspName}"
        // supports() is null while a server is not ready, whatever the capability.
        val ready = serversOfSession(session).filter { it.supports(WORKSPACE) != null }
        if (ready.isEmpty()) return null
        val changes = mutableListOf<FileChange>()
        for (server in ready) {
            val concerned = filesMatching(server.capability(WORKSPACE), name, files)
            if (concerned.isNotEmpty()) changes += editsOf(server, operation, concerned)
        }
        return changes
    }

    // The edits [server] makes before [operation]; none when it does not answer in time, refuses or asks for more.
    private suspend fun editsOf(
        server: ManagedServer,
        operation: FileOperation,
        files: List<OperatedFile>,
    ): List<FileChange> {
        val params = fileOperationParams(operation, files)
        val method = "workspace/will${operation.lspName}Files"
        return requests.answeredBy(
            server,
            emptyList(),
        ) { request -> workspaceEditOf(request(method, params)).orEmpty() }
    }
}
