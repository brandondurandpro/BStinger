package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.CodeActionsResult
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FileDiagnostics
import org.example.kotlineditor.model.FoldingRangesResult
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.HierarchyKind
import org.example.kotlineditor.model.HierarchyResult
import org.example.kotlineditor.model.HoverResult
import org.example.kotlineditor.model.InlayHintsResult
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.OutlineResult
import org.example.kotlineditor.model.RenameTarget
import org.example.kotlineditor.model.SelectionRangesResult
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCount
import org.example.kotlineditor.model.UsageCountsResult
import org.example.kotlineditor.model.WorkspaceSymbol
import org.example.kotlineditor.model.WorkspaceSymbolsResult
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val URI = "uri"
private const val TEXT_DOCUMENT = "textDocument"
private const val RANGE = "range"
private const val COMMAND = "command"
private const val RENAME_PROVIDER = "renameProvider"
private const val POSITION = "position"
private const val EDIT = "edit"
private const val CODE_ACTION_PROVIDER = "codeActionProvider"
private const val DOCUMENT_SYMBOL_PROVIDER = "documentSymbolProvider"
private const val WORKSPACE_SYMBOL_PROVIDER = "workspaceSymbolProvider"

/** Delays and limits of the code intelligence requests. */
class IntelligenceSettings(
    /** How long a request waits for the document version it is about, then for the language server's answer. */
    val timeoutMs: Long = 10_000,
    /** How long a diagnostics request waits for a change before answering the current diagnostics. */
    val holdMs: Long = 20_000,
    /** Most usages or definitions sent for one request. */
    val maxLocations: Int = 1_000,
    /** Most declarations of a document whose usages are counted. */
    val maxCountedSymbols: Int = 150,
    /** References requests run at once to count usages. */
    val countParallelism: Int = 4,
)

/** A code action offered to a session, kept until it asks for another list. */
private class OfferedActions(val token: String, val server: ManagedServer, val actions: List<JsonObject>)

/** Diagnostics of a document as one language server published them. */
private data class Published(val server: String, val uri: String, val diagnostics: List<Diagnostic>)

/**
 * The code intelligence of the open documents (see #97): hover, navigation, usages, highlights, signatures,
 * diagnostics, rename, formatting and code actions, asked to the language server of each document once the version
 * the client means arrived, and translated for the IDE.
 */
@Suppress("TooManyFunctions")
class CodeIntelligenceService internal constructor(
    private val documents: OpenDocuments,
    private val serversOf: (MirroredDocument) -> List<ManagedServer>,
    private val settings: IntelligenceSettings = IntelligenceSettings(),
    private val serversOfSession: (String) -> List<ManagedServer> = { emptyList() },
) {
    private val requests = ServerRequests(documents, serversOf, settings)
    private val structure = DocumentStructureRequests(documents, requests)
    private val hierarchy = HierarchyRequests(requests) { withPreviews(it) }
    private val diagnostics = ConcurrentHashMap<String, Published>()
    private val diagnosticsVersion = MutableStateFlow(1L)
    private val offered = ConcurrentHashMap<String, OfferedActions>()
    private val tokens = AtomicLong()
    private val usageCache = ConcurrentHashMap<String, Pair<Int, UsageCountsResult>>()

    // Edits a running command asks for, by server id; a command runs alone on its server.
    private val collectors = ConcurrentHashMap<String, MutableList<FileChange>>()
    private val commandLocks = ConcurrentHashMap<String, Mutex>()

    /** Documentation at [at]. */
    suspend fun hover(session: String, at: DocumentPosition): HoverResult =
        requests.ask(
            session,
            at.path,
            at.version,
            "hoverProvider",
            HoverResult.NotReady,
            HoverResult(FeatureStatus.Unsupported, null),
        ) {
                document,
                request,
            ->
            val answer = request("textDocument/hover", positionParams(document, at))
            val range = (answer as? JsonObject)?.get(RANGE).textRange()
            HoverResult(FeatureStatus.Ready, hoverMarkdown(answer), range)
        }

    /** Declaration, type or implementations of the symbol at [at]. */
    suspend fun navigate(session: String, at: DocumentPosition, kind: NavigationKind): LocationsResult {
        val (method, capability) =
            when (kind) {
                NavigationKind.Definition -> "textDocument/definition" to "definitionProvider"
                NavigationKind.TypeDefinition -> "textDocument/typeDefinition" to "typeDefinitionProvider"
                NavigationKind.Implementation -> "textDocument/implementation" to "implementationProvider"
            }
        return locations(session, at, method, capability, positionParams(at))
    }

    /** Usages of the symbol at [at]. */
    suspend fun references(session: String, at: DocumentPosition, includeDeclaration: Boolean): LocationsResult {
        val params = referencesParams(at.line, at.column, includeDeclaration)
        return locations(session, at, "textDocument/references", "referencesProvider", params)
    }

    private suspend fun locations(
        session: String,
        at: DocumentPosition,
        method: String,
        capability: String,
        params: (MirroredDocument) -> JsonObject,
    ): LocationsResult =
        requests.ask(
            session,
            at.path,
            at.version,
            capability,
            LocationsResult.NotReady,
            LocationsResult.Unsupported,
        ) { document, request ->
            val found = locationsOf(request(method, params(document)))
            val kept = found.take(settings.maxLocations)
            LocationsResult(FeatureStatus.Ready, withPreviews(kept), truncated = found.size > kept.size)
        }

    /** Occurrences of the symbol at [at] in its document. */
    suspend fun highlights(session: String, at: DocumentPosition): List<SymbolHighlight> =
        requests.ask(
            session,
            at.path,
            at.version,
            "documentHighlightProvider",
            emptyList(),
            emptyList(),
        ) { document, request ->
            highlightsOf(request("textDocument/documentHighlight", positionParams(document, at)))
        }

    /** Signatures of the call around [at]. */
    suspend fun signatureHelp(session: String, at: DocumentPosition): SignatureHelpResult =
        requests.ask(
            session,
            at.path,
            at.version,
            "signatureHelpProvider",
            SignatureHelpResult.NotReady,
            SignatureHelpResult.NotReady.copy(status = FeatureStatus.Unsupported),
        ) { document, request ->
            signatureHelpOf(request("textDocument/signatureHelp", positionParams(document, at)))
        }

    /** The declarations of [path] at [version], nested, for File Structure. */
    suspend fun outline(session: String, path: String, version: Int): OutlineResult =
        structure.outline(session, path, version)

    /** The function or type at [at], root of the call or type hierarchy [kind]. */
    suspend fun prepareHierarchy(session: String, at: DocumentPosition, kind: HierarchyKind): HierarchyResult =
        hierarchy.prepare(session, at, kind)

    /** The callers, callees, supertypes or subtypes of [item], from the server of the open document [path]. */
    suspend fun hierarchyChildren(path: String, item: HierarchyItem, direction: HierarchyDirection): HierarchyResult =
        hierarchy.children(path, item, direction)

    /** The inlay hints of version [version] of [path], over the whole document. */
    suspend fun inlayHints(session: String, path: String, version: Int): InlayHintsResult =
        structure.inlayHints(session, path, version)

    /** The regions of version [version] of [path] that can be folded. */
    suspend fun foldingRanges(session: String, path: String, version: Int): FoldingRangesResult =
        structure.foldingRanges(session, path, version)

    /** The ranges Ctrl+W selects around [at], innermost first. */
    suspend fun selectionRanges(session: String, at: DocumentPosition): SelectionRangesResult =
        structure.selectionRanges(session, at)

    /** The declarations of [session]'s project named like [query], from every server of the project that searches. */
    suspend fun workspaceSymbols(session: String, query: String): WorkspaceSymbolsResult {
        val servers = serversOfSession(session).filter { it.supports(WORKSPACE_SYMBOL_PROVIDER) != null }
        val searching = servers.filter { it.supports(WORKSPACE_SYMBOL_PROVIDER) == true }
        val unsupported = servers.isNotEmpty() && searching.isEmpty()
        val found = if (searching.isEmpty()) emptyList() else searchSymbols(searching, query)
        val status =
            when {
                unsupported -> FeatureStatus.Unsupported
                found.isEmpty() -> FeatureStatus.NotReady
                else -> FeatureStatus.Ready
            }
        return WorkspaceSymbolsResult(status, found.flatten().take(settings.maxLocations))
    }

    // The answers of the [servers] that answered.
    private suspend fun searchSymbols(servers: List<ManagedServer>, query: String): List<List<WorkspaceSymbol>> {
        val params = buildJsonObject { put("query", query) }
        val answers =
            coroutineScope {
                val asked =
                    servers.map { server ->
                        async {
                            requests.answeredBy<List<WorkspaceSymbol>?>(server, null) { request ->
                                workspaceSymbolsOf(request("workspace/symbol", params))
                            }
                        }
                    }
                asked.awaitAll()
            }
        return answers.filterNotNull()
    }

    /**
     * How many times each declaration of [path] is used: its symbols, then the references of each, a few at a time.
     * Kept for the version of the document it was counted on.
     */
    suspend fun usageCounts(session: String, path: String, version: Int): UsageCountsResult {
        val notReady = UsageCountsResult(FeatureStatus.NotReady, emptyList())
        val unsupported = UsageCountsResult(FeatureStatus.Unsupported, emptyList())
        return requests.ask(
            session,
            path,
            version,
            DOCUMENT_SYMBOL_PROVIDER,
            notReady,
            unsupported,
        ) { document, request ->
            val lspVersion = documents.locked { document.lspVersion }
            val cached = usageCache[document.uri]?.takeIf { it.first == lspVersion }
            cached?.second ?: countUsages(document, request).also { usageCache[document.uri] = lspVersion to it }
        }
    }

    private suspend fun countUsages(
        document: MirroredDocument,
        request: suspend (String, JsonObject) -> JsonElement?,
    ): UsageCountsResult {
        val symbolParams = buildJsonObject { putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) } }
        val symbols =
            countedSymbolsOf(
                request("textDocument/documentSymbol", symbolParams),
            ).take(settings.maxCountedSymbols)
        val supportsReferences = serversOf(document).any { it.supports("referencesProvider") == true }
        if (!supportsReferences) return UsageCountsResult(FeatureStatus.Unsupported, emptyList())
        val permits = Semaphore(settings.countParallelism)
        val counts =
            coroutineScope {
                val asked =
                    symbols.map { symbol ->
                        async {
                            permits.withPermit {
                                usageCount(
                                    document,
                                    symbol,
                                    request,
                                )
                            }
                        }
                    }
                asked.awaitAll()
            }
        return UsageCountsResult(FeatureStatus.Ready, counts)
    }

    private suspend fun usageCount(
        document: MirroredDocument,
        symbol: CountedSymbol,
        request: suspend (String, JsonObject) -> JsonElement?,
    ): UsageCount {
        val start = symbol.range
        val params = referencesParams(start.startLine, start.startColumn, false)(document)
        val usages = locationsOf(request("textDocument/references", params))
        // Some servers count the declaration even when asked not to.
        val others = usages.filterNot { it.path == document.path && contains(it.range, start) }
        return UsageCount(symbol.range, symbol.name, others.size)
    }

    /** The diagnostics of [session]'s documents once newer than [since], or the current ones after a while. */
    suspend fun diagnostics(session: String, since: Long): DiagnosticsSnapshot {
        val current = diagnosticsVersion.value
        if (since == current) withTimeoutOrNull(settings.holdMs) { diagnosticsVersion.first { it > since } }
        val version = diagnosticsVersion.value
        val opened = documents.openedBy(session)
        val files =
            opened.map { document ->
                val found = diagnostics.values.filter { it.uri == document.uri }.flatMap { it.diagnostics }
                FileDiagnostics(
                    document.path,
                    found.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn })),
                )
            }
        return DiagnosticsSnapshot(version, files)
    }

    /** What can be renamed at [at]. */
    suspend fun prepareRename(session: String, at: DocumentPosition): RenameTarget {
        val notReady = RenameTarget(FeatureStatus.NotReady, null, null)
        val unsupported = RenameTarget(FeatureStatus.Unsupported, null, null)
        return requests.ask(
            session,
            at.path,
            at.version,
            RENAME_PROVIDER,
            notReady,
            unsupported,
            ::refusedRename,
        ) { document, request ->
            val server = serversOf(document).firstOrNull { it.supports(RENAME_PROVIDER) == true }
            val provider = server?.capability(RENAME_PROVIDER) as? JsonObject
            val prepares = (provider?.get("prepareProvider") as? JsonPrimitive)?.booleanOrNull == true
            val word = wordAt(documents.locked { document.line(at.line) }, at.line, at.column)
            if (prepares) {
                val answer = request("textDocument/prepareRename", positionParams(document, at))
                renameTargetOf(answer, document, word)
            } else {
                RenameTarget(FeatureStatus.Ready, word?.first, word?.second)
            }
        }
    }

    private fun refusedRename(failure: LspResponseException) =
        RenameTarget(FeatureStatus.Ready, null, null, failure.message?.substringBefore(" at ")?.take(MAX_MESSAGE))

    private fun renameTargetOf(
        answer: JsonElement?,
        document: MirroredDocument,
        word: Pair<TextRange, String>?,
    ): RenameTarget {
        val fields =
            answer as? JsonObject ?: return RenameTarget(
                FeatureStatus.Ready,
                null,
                null,
                "Cannot rename this element",
            )
        val defaultBehavior = (fields["defaultBehavior"] as? JsonPrimitive)?.booleanOrNull == true
        val range = fields.textRangeOrNull() ?: fields[RANGE].textRange() ?: word?.first.takeIf { defaultBehavior }
        val name = fields.string("placeholder") ?: range?.let { textOf(document, it) }
        return RenameTarget(FeatureStatus.Ready, range, name)
    }

    private fun JsonObject.textRangeOrNull(): TextRange? = this.takeIf { "start" in it }.textRange()

    /** The edits renaming the symbol at [at] to [newName]. */
    suspend fun rename(session: String, at: DocumentPosition, newName: String): EditResult =
        requests.ask(
            session,
            at.path,
            at.version,
            RENAME_PROVIDER,
            EditResult.NotReady,
            EditResult.Unsupported,
            ::refusedEdit,
        ) { document, request ->
            val params =
                buildJsonObject {
                    putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                    put(POSITION, position(at.line, at.column))
                    put("newName", newName)
                }
            editResultOf(workspaceEditOf(request("textDocument/rename", params)))
        }

    private fun refusedEdit(failure: LspResponseException) =
        EditResult(FeatureStatus.Ready, emptyList(), failure.message?.substringBefore(" at ")?.take(MAX_MESSAGE))

    private fun editResultOf(changes: List<FileChange>?): EditResult =
        if (changes == null) {
            EditResult(
                FeatureStatus.Ready,
                emptyList(),
                "The language server asked for file operations the IDE does not support",
            )
        } else {
            EditResult(FeatureStatus.Ready, changes.map(::withClientPath))
        }

    /** The edits formatting [path], or [range] of it. */
    suspend fun format(
        session: String,
        path: String,
        version: Int,
        range: TextRange?,
        options: FormatOptions,
    ): EditResult {
        val capability = if (range == null) "documentFormattingProvider" else "documentRangeFormattingProvider"
        val method = if (range == null) "textDocument/formatting" else "textDocument/rangeFormatting"
        return requests.ask(
            session,
            path,
            version,
            capability,
            EditResult.NotReady,
            EditResult.Unsupported,
            ::refusedEdit,
        ) { document, request ->
            val params =
                buildJsonObject {
                    putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                    range?.let { put(RANGE, lspRange(it)) }
                    putJsonObject("options") {
                        put("tabSize", options.tabSize)
                        put("insertSpaces", options.insertSpaces)
                        put("trimTrailingWhitespace", true)
                    }
                }
            val edits = textChangesOf(request(method, params))
            EditResult(
                FeatureStatus.Ready,
                if (edits.isEmpty()) emptyList() else listOf(FileChange(document.path, edits)),
            )
        }
    }

    /** The code actions offered for [range] of [path], with the diagnostics found there. */
    suspend fun codeActions(session: String, path: String, version: Int, range: TextRange): CodeActionsResult {
        val unsupported = CodeActionsResult(FeatureStatus.Unsupported, "", emptyList())
        return requests.ask(
            session,
            path,
            version,
            CODE_ACTION_PROVIDER,
            CodeActionsResult.NotReady,
            unsupported,
        ) { document, request ->
            val server =
                serversOf(
                    document,
                ).firstOrNull { it.supports(CODE_ACTION_PROVIDER) == true } ?: throw NotReadyException()
            val params =
                buildJsonObject {
                    putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                    put(RANGE, lspRange(range))
                    putJsonObject("context") { put("diagnostics", diagnosticsAt(document, range)) }
                }
            val actions = codeActionsOf(request("textDocument/codeAction", params))
            val token = "${session.hashCode()}-${tokens.incrementAndGet()}"
            offered[session] = OfferedActions(token, server, actions.map { it.second })
            CodeActionsResult(FeatureStatus.Ready, token, actions.map { it.first })
        }
    }

    // The raw diagnostics touching [range], as the server sent them: they tell it what to fix.
    private fun diagnosticsAt(document: MirroredDocument, range: TextRange): JsonArray {
        val touching =
            diagnostics.values
                .filter { it.uri == document.uri }
                .flatMap { it.diagnostics }
                .filter { overlaps(it.range, range) }
        return JsonArray(
            touching.map { diagnostic ->
                buildJsonObject {
                    put(RANGE, lspRange(diagnostic.range))
                    put("severity", diagnostic.severity.ordinal + 1)
                    put("message", diagnostic.message)
                    diagnostic.code?.let { put("code", it) }
                    diagnostic.source?.let { put("source", it) }
                }
            },
        )
    }

    /**
     * The edits of action [index] of [token]: its edit, then what its command asks for while it runs. A command
     * runs once: the protocol answers a repeated request with the first answer.
     */
    suspend fun applyCodeAction(session: String, token: String, index: Int): EditResult {
        val list = offered[session]?.takeIf { it.token == token }
        val action =
            list?.actions?.getOrNull(
                index,
            ) ?: return EditResult(FeatureStatus.Ready, emptyList(), "This action is no longer available")
        return try {
            withTimeoutOrNull(
                settings.timeoutMs,
            ) { run(list.server, resolved(list.server, action)) } ?: EditResult.NotReady
        } catch (_: NotReadyException) {
            EditResult.NotReady
        } catch (_: LspConnectionClosedException) {
            EditResult.NotReady
        } catch (failure: LspResponseException) {
            refusedEdit(failure)
        }
    }

    // A code action with neither edit nor command is resolved first, when the server can.
    private suspend fun resolved(server: ManagedServer, action: JsonObject): JsonObject {
        val complete = EDIT in action || COMMAND in action
        val provider = server.capability(CODE_ACTION_PROVIDER) as? JsonObject
        val resolves = (provider?.get("resolveProvider") as? JsonPrimitive)?.booleanOrNull == true
        if (complete || !resolves) return action
        return server.request("codeAction/resolve", action) as? JsonObject ?: action
    }

    private suspend fun run(server: ManagedServer, action: JsonObject): EditResult {
        val edit = action[EDIT]?.let(::workspaceEditOf) ?: emptyList()
        // A Command is itself `{title, command: "name", arguments}`; a CodeAction has `command: {command, arguments}`.
        val nested = action[COMMAND] as? JsonObject
        val command = nested ?: action.takeIf { action[COMMAND] is JsonPrimitive }
        val collected = command?.let { execute(server, it) } ?: emptyList()
        val merged = mergeChanges(edit + collected)
        return EditResult(FeatureStatus.Ready, merged.map(::withClientPath))
    }

    private suspend fun execute(server: ManagedServer, command: JsonObject): List<FileChange> {
        val lock = commandLocks.getOrPut(server.id) { Mutex() }
        return lock.withLock {
            val edits = mutableListOf<FileChange>()
            collectors[server.id] = edits
            try {
                val params =
                    buildJsonObject {
                        put(COMMAND, command[COMMAND] ?: JsonNull)
                        command["arguments"]?.let { put("arguments", it) }
                    }
                server.request("workspace/executeCommand", params) ?: throw NotReadyException()
                synchronized(edits) { edits.toList() }
            } finally {
                collectors.remove(server.id)
            }
        }
    }

    /** A server's `workspace/applyEdit`: taken while one of its commands runs for a client, refused otherwise. */
    internal fun applyEdit(server: String, params: JsonObject): Boolean {
        val edits = collectors[server]
        val changes = edits?.let { workspaceEditOf(params[EDIT]) }
        if (edits != null && changes != null) synchronized(edits) { edits += changes }
        return changes != null
    }

    /** A server published the diagnostics of a document. */
    internal fun published(server: String, params: JsonObject) {
        val uri = params.string(URI) ?: return
        val document = documents.byUri(uri) ?: return
        diagnostics["$server $uri"] = Published(server, document.uri, diagnosticsOf(params))
        diagnosticsVersion.value++
    }

    /** A new process of [server] is ready: the diagnostics of the previous one are obsolete. */
    internal fun restarted(server: String) {
        val removed = diagnostics.keys.removeIf { it.startsWith("$server ") }
        usageCache.clear()
        if (removed) diagnosticsVersion.value++
    }

    /** [document] is not open any more. */
    internal fun closed(document: MirroredDocument) {
        val removed = diagnostics.values.removeIf { it.uri == document.uri }
        usageCache.remove(document.uri)
        if (removed) diagnosticsVersion.value++
    }

    /** [session] ended. */
    internal fun released(session: String) {
        offered.remove(session)
        diagnosticsVersion.value++
    }

    // The locations with the text of their first line, read from the open documents, else from the disk.
    private fun withPreviews(locations: List<SourceLocation>): List<SourceLocation> {
        val files = HashMap<String, List<String>?>()
        return locations.map { location ->
            val line = location.range.startLine
            val open = documents.byUri(Paths.get(location.path).toUri().toString())
            val preview =
                if (open != null) {
                    documents.locked { open.line(line) }
                } else {
                    files.getOrPut(location.path) { readLines(location.path) }?.getOrNull(line)
                }
            val path = open?.path ?: location.path
            location.copy(path = path, preview = preview?.take(MAX_PREVIEW))
        }
    }

    private fun readLines(path: String): List<String>? =
        try {
            Files.readAllLines(Paths.get(path))
        } catch (_: IOException) {
            null
        } catch (_: MalformedInputException) {
            null
        }

    // An open document is named as its client names it.
    private fun withClientPath(change: FileChange): FileChange {
        val open = documents.byUri(Paths.get(change.path).toUri().toString())
        return if (open == null) change else change.copy(path = open.path)
    }

    private fun mergeChanges(changes: List<FileChange>): List<FileChange> =
        changes.groupBy { it.path }.map { (path, same) ->
            FileChange(
                path,
                same.flatMap { it.edits },
                same.any { it.create },
            )
        }

    private fun textOf(document: MirroredDocument, range: TextRange): String? {
        if (range.startLine != range.endLine) return null
        val line = documents.locked { document.line(range.startLine) }
        val start = range.startColumn.coerceIn(0, line.length)
        val end = range.endColumn.coerceIn(start, line.length)
        return line.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun positionParams(at: DocumentPosition): (MirroredDocument) -> JsonObject = { positionParams(it, at) }

    private fun positionParams(document: MirroredDocument, at: DocumentPosition) =
        buildJsonObject {
            putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
            put(POSITION, position(at.line, at.column))
        }

    private fun referencesParams(
        line: Int,
        column: Int,
        includeDeclaration: Boolean,
    ): (MirroredDocument) -> JsonObject =
        { document ->
            buildJsonObject {
                putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                put(POSITION, position(line, column))
                putJsonObject("context") { put("includeDeclaration", includeDeclaration) }
            }
        }

    private fun position(line: Int, column: Int) =
        buildJsonObject {
            put("line", line)
            put("character", column)
        }

    private companion object {
        const val MAX_PREVIEW = 300
        const val MAX_MESSAGE = 300
    }
}

/** Whether [inner] is inside [outer]. */
internal fun contains(outer: TextRange, inner: TextRange): Boolean {
    val startsAfter = comparePositions(inner.startLine, inner.startColumn, outer.startLine, outer.startColumn) >= 0
    val endsBefore = comparePositions(inner.endLine, inner.endColumn, outer.endLine, outer.endColumn) <= 0
    return startsAfter && endsBefore
}

/** Whether [first] and [second] share a position (an empty range touching another counts). */
internal fun overlaps(first: TextRange, second: TextRange): Boolean {
    val firstEndsBefore = comparePositions(first.endLine, first.endColumn, second.startLine, second.startColumn) < 0
    val secondEndsBefore = comparePositions(second.endLine, second.endColumn, first.startLine, first.startColumn) < 0
    return !firstEndsBefore && !secondEndsBefore
}

// Negative when the first position comes before the second one.
private fun comparePositions(line: Int, column: Int, otherLine: Int, otherColumn: Int): Int {
    val byLine = line.compareTo(otherLine)
    return if (byLine != 0) byLine else column.compareTo(otherColumn)
}

/** The identifier around [column] of [text] (line [line]) and its range, PHP's `$` included. */
internal fun wordAt(text: String, line: Int, column: Int): Pair<TextRange, String>? {
    val isWordChar = { c: Char -> c.isLetterOrDigit() || c == '_' || c == '$' }
    var start = column.coerceIn(0, text.length)
    var end = start
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < text.length && isWordChar(text[end])) end++
    if (start == end) return null
    return TextRange(line, start, line, end) to text.substring(start, end)
}
