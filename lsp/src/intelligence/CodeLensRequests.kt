package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.CodeLensAction
import org.example.kotlineditor.model.CodeLensInfo
import org.example.kotlineditor.model.CodeLensesResult
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.SourceLocation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val CODE_LENS_PROVIDER = "codeLensProvider"
private const val COMMAND = "command"
private const val ARGUMENTS = "arguments"

// The client-side command of VS Code that lists places: `[uri, position, locations]`.
private const val SHOW_REFERENCES = "editor.action.showReferences"
private const val LOCATIONS_ARGUMENT = 2

/** The commands of the lenses of one document offered to a session, kept until it asks for them again. */
private class OfferedLenses(val session: String, val server: ManagedServer, val commands: List<JsonObject>)

/**
 * The code lenses of a document (`textDocument/codeLens`, resolved with `codeLens/resolve` when the server leaves
 * their command out) and running the command of one, on the server.
 */
internal class CodeLensRequests(
    private val requests: ServerRequests,
    private val run: suspend (ManagedServer, JsonObject) -> EditResult,
    private val withPreviews: (List<SourceLocation>) -> List<SourceLocation>,
    private val maxResolved: Int = MAX_RESOLVED,
) {
    private val offered = ConcurrentHashMap<String, OfferedLenses>()
    private val latest = ConcurrentHashMap<String, String>()
    private val tokens = AtomicLong()

    /** The lenses of version [version] of [path], in document order. */
    suspend fun codeLenses(session: String, path: String, version: Int): CodeLensesResult {
        val unsupported = CodeLensesResult(FeatureStatus.Unsupported, "", emptyList())
        return requests.ask(session, path, version, CODE_LENS_PROVIDER, CodeLensesResult.NotReady, unsupported) {
                document,
                request,
            ->
            val server = requests.serverOf(path, CODE_LENS_PROVIDER) ?: throw NotReadyException()
            val params = buildJsonObject { putJsonObject("textDocument") { put("uri", document.uri) } }
            val answer = request("textDocument/codeLens", params) as? JsonArray
            val raw = answer.orEmpty().mapNotNull { it as? JsonObject }
            val shown = resolved(server, raw, request).mapNotNull { lens -> lensOf(server, lens) }
            val sorted = shown.sortedWith(compareBy({ it.first.range.startLine }, { it.first.range.startColumn }))
            val token = offer(session, path, server, sorted.map { it.second })
            CodeLensesResult(FeatureStatus.Ready, token, sorted.map { it.first })
        }
    }

    /** Runs the command of lens [index] of the list [token] on its server: the edits it asked for. */
    suspend fun runCodeLens(session: String, token: String, index: Int): EditResult {
        val lenses = offered[token]?.takeIf { it.session == session }
        val command = lenses?.commands?.getOrNull(index)
        if (lenses == null || command == null) {
            return EditResult(FeatureStatus.Ready, emptyList(), "This lens is no longer available")
        }
        return run(lenses.server, command)
    }

    /** [session] ended: its lenses are forgotten. */
    fun released(session: String) {
        offered.values.removeIf { it.session == session }
        latest.keys.removeIf { it.startsWith("$session ") }
    }

    // A new list replaces the previous one of the same document.
    private fun offer(session: String, path: String, server: ManagedServer, commands: List<JsonObject>): String {
        val token = "${session.hashCode()}-${tokens.incrementAndGet()}"
        offered[token] = OfferedLenses(session, server, commands)
        latest.put("$session $path", token)?.let { offered.remove(it) }
        return token
    }

    // The lenses without a command are resolved, at once, when the server can; the first [maxResolved] only.
    private suspend fun resolved(
        server: ManagedServer,
        lenses: List<JsonObject>,
        request: suspend (String, JsonObject) -> JsonElement?,
    ): List<JsonObject> {
        val provider = server.capability(CODE_LENS_PROVIDER) as? JsonObject
        val resolves = (provider?.get("resolveProvider") as? JsonPrimitive)?.booleanOrNull == true
        if (!resolves) return lenses
        var left = maxResolved
        return coroutineScope {
            val answers =
                lenses.map { lens ->
                    val resolve = lens[COMMAND] == null && left-- > 0
                    async { if (resolve) request("codeLens/resolve", lens) as? JsonObject else null }
                }
            answers.awaitAll().zip(lenses) { answer, lens -> answer ?: lens }
        }
    }

    // A lens with a title, and the command a click runs; null when it has no command yet.
    private fun lensOf(server: ManagedServer, lens: JsonObject): Pair<CodeLensInfo, JsonObject>? {
        val range = lens["range"].textRange()
        val command = (lens[COMMAND] as? JsonObject)?.takeIf { !it.string("title").isNullOrBlank() }
        if (range == null || command == null) return null
        val title = command.string("title").orEmpty()
        val name = command.string(COMMAND)
        val arguments = command[ARGUMENTS] as? JsonArray
        val locations = withPreviews(locationsOf(arguments?.getOrNull(LOCATIONS_ARGUMENT)))
        val action =
            when {
                name != null && name in commandsOf(server) -> CodeLensAction.Run
                name == SHOW_REFERENCES -> CodeLensAction.ShowLocations
                else -> CodeLensAction.None
            }
        val listed = locations.takeIf { action == CodeLensAction.ShowLocations }.orEmpty()
        return CodeLensInfo(range, title, action, listed) to command
    }

    private fun commandsOf(server: ManagedServer): Set<String> {
        val provider = server.capability("executeCommandProvider") as? JsonObject
        val commands = provider?.array("commands").orEmpty()
        return commands.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    }

    private companion object {
        const val MAX_RESOLVED = 1_000
    }
}
