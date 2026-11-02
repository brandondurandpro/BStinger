package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.TypeFormattingTriggers

private const val PROVIDER = "documentOnTypeFormattingProvider"

/** Formatting while typing (`textDocument/onTypeFormatting`): after a trigger character the server formats around. */
internal class TypeFormattingRequests(
    private val requests: ServerRequests,
    private val refused: (LspResponseException) -> EditResult,
) {
    /** The trigger characters of the server of [path]. */
    suspend fun triggers(session: String, path: String, version: Int): TypeFormattingTriggers {
        val unsupported = TypeFormattingTriggers(FeatureStatus.Unsupported, emptyList())
        return requests.ask(session, path, version, PROVIDER, TypeFormattingTriggers.NotReady, unsupported) { _, _ ->
            val server = requests.serverOf(path, PROVIDER) ?: throw NotReadyException()
            TypeFormattingTriggers(FeatureStatus.Ready, triggersOf(server.capability(PROVIDER) as? JsonObject))
        }
    }

    /** The edits the server makes after [character] was typed just before [at]; none for another character. */
    suspend fun format(session: String, at: DocumentPosition, character: String, options: FormatOptions): EditResult =
        requests.ask(session, at.path, at.version, PROVIDER, EditResult.NotReady, EditResult.Unsupported, refused) {
                document,
                request,
            ->
            val server = requests.serverOf(at.path, PROVIDER) ?: throw NotReadyException()
            val triggers = triggersOf(server.capability(PROVIDER) as? JsonObject)
            val edits =
                if (character in triggers) {
                    val params =
                        buildJsonObject {
                            putJsonObject("textDocument") { put("uri", document.uri) }
                            putJsonObject("position") {
                                put("line", at.line)
                                put("character", at.column)
                            }
                            put("ch", character)
                            putJsonObject("options") {
                                put("tabSize", options.tabSize)
                                put("insertSpaces", options.insertSpaces)
                            }
                        }
                    textChangesOf(request("textDocument/onTypeFormatting", params))
                } else {
                    emptyList()
                }
            EditResult(
                FeatureStatus.Ready,
                if (edits.isEmpty()) emptyList() else listOf(FileChange(document.path, edits)),
            )
        }
}

/** `firstTriggerCharacter` and `moreTriggerCharacter` of a documentOnTypeFormattingProvider. */
fun triggersOf(provider: JsonObject?): List<String> {
    val first = (provider?.get("firstTriggerCharacter") as? JsonPrimitive)?.content
    val more =
        (
            provider?.get(
                "moreTriggerCharacter",
            ) as? JsonArray
            ).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    return listOfNotNull(first) + more
}
