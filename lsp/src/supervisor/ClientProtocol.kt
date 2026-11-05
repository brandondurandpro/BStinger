package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.SyncKind
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import java.nio.file.Paths

private const val CAPABILITIES = "capabilities"
private const val FULL_SYNC = 1

/**
 * The IDE's side of the handshake with a language server of [target], and its answers to the requests the server
 * sends to its client; what they report goes to [hooks], as about the server [serverId].
 */
internal class ClientProtocol(
    private val serverId: String,
    private val target: ServerTarget,
    private val hooks: LanguageServerHooks,
) {
    /** The parameters of `initialize`: the root, the client's capabilities and the server's options. */
    fun initializeParams(): JsonObject =
        buildJsonObject {
            put("processId", ProcessHandle.current().pid())
            putJsonObject("clientInfo") { put("name", "Kotlin Editor") }
            put("rootUri", rootUri())
            put("rootPath", target.root)
            putJsonArray("workspaceFolders") { add(folder()) }
            putJsonObject(CAPABILITIES) {
                putJsonObject("window") { put("workDoneProgress", true) }
                put("workspace", WORKSPACE_CAPABILITIES)
                put("textDocument", TEXT_DOCUMENT_CAPABILITIES)
            }
            target.spec.initializationOptions?.let { put("initializationOptions", it) }
        }

    /** The requests a language server sends to its client: answered with what we have, never left waiting. */
    fun answer(method: String, params: JsonElement?): JsonElement =
        when (method) {
            "workspace/configuration" -> {
                val items = (params as? JsonObject)?.get("items")?.jsonArray?.size ?: 0
                JsonArray(List(items) { JsonNull })
            }

            "workspace/workspaceFolders" -> {
                buildJsonArray { add(folder()) }
            }

            "workspace/diagnostic/refresh" -> {
                hooks.onDiagnosticsRefresh(serverId)
                JsonNull
            }

            "workspace/applyEdit" -> {
                val edit = params as? JsonObject
                val applied = edit != null && hooks.onApplyEdit(serverId, edit)
                buildJsonObject { put("applied", applied) }
            }

            "window/workDoneProgress/create", "client/registerCapability", "client/unregisterCapability",
            "window/showMessageRequest", "workspace/semanticTokens/refresh", "workspace/inlayHint/refresh",
            "workspace/codeLens/refresh",
            -> {
                JsonNull
            }

            else -> {
                throw LspResponseException(LspConnection.METHOD_NOT_FOUND, "Unhandled method $method")
            }
        }

    private fun folder() =
        buildJsonObject {
            put("uri", rootUri())
            put("name", Paths.get(target.root).fileName?.toString() ?: target.root)
        }

    private fun rootUri() = Paths.get(target.root).toUri().toString()
}

/** The capabilities of the answer to `initialize`, empty when it gives none. */
internal fun capabilitiesOf(initialized: JsonElement?): JsonObject {
    val answer = initialized as? JsonObject
    val offered = answer?.get(CAPABILITIES) as? JsonObject
    return offered ?: JsonObject(emptyMap())
}

/** textDocumentSync is a number or an object whose change is one; Full is 1, Incremental 2, None 0. */
internal fun syncKindOf(initialized: JsonElement?): SyncKind {
    val initializeResult = initialized as? JsonObject
    val capabilities = initializeResult?.get(CAPABILITIES) as? JsonObject
    val syncElement = capabilities?.get("textDocumentSync")

    val syncAsPrimitive = syncElement as? JsonPrimitive
    val syncAsObject = syncElement as? JsonObject
    val changeElement = syncAsObject?.get("change") as? JsonPrimitive

    val kindFromPrimitive = syncAsPrimitive?.intOrNull
    val kindFromChangeObject = changeElement?.intOrNull
    val finalKind = kindFromPrimitive ?: kindFromChangeObject

    return if (finalKind == FULL_SYNC) {
        SyncKind.Full
    } else {
        SyncKind.Incremental
    }
}
