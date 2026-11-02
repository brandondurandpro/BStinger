package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonObject

/** What the language servers say besides their answers, by server id: the service routes it to the sessions. */
class LanguageServerHooks(
    /** A `textDocument/publishDiagnostics` notification. */
    val onDiagnostics: (server: String, params: JsonObject) -> Unit = { _, _ -> },
    /** A `workspace/applyEdit` request: true when the edit is taken (it reaches the client that asked for it). */
    val onApplyEdit: (server: String, params: JsonObject) -> Boolean = { _, _ -> false },
    /** A new process of the server is ready: what the previous one published is obsolete. */
    val onRestarted: (server: String) -> Unit = {},
)
