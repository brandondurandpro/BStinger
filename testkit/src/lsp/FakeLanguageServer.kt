package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A real language server on stdin and stdout, which tests start as a process to reproduce what real servers do
 * wrong: crash, hang, ignore shutdown, print garbage, flood stderr, send huge answers, ask questions, leak memory.
 *
 * It answers `initialize` (incremental sync, hover, completion), `shutdown` and `exit` (exit code 0 after shutdown, 1
 * otherwise, 1 too when stdin closes), echoes the position of `textDocument/hover`, and publishes one diagnostic per
 * opened document with `--diagnostics`. It keeps the text of the open documents, applying incremental changes, and
 * completes like a PHP server: the functions declared in the open documents, the variables of the document, and a
 * few built-in functions (`strlen`, `strtoupper`...), each with an edit replacing the word before the caret, `$`
 * included. Options, combinable:
 * - memory: `--leak-mb-per-s <n>`, `--sawtooth <lowMb>:<highMb>:<periodS>`,
 *   `--spike-then-plateau <spikeMb>:<plateauMb>:<spikeS>`, `--child-mb <n>` (a child process holding n MB);
 * - lifecycle: `--crash-on-start`, `--crash-after <n requests>` (exit code 3), `--hang-on-initialize`,
 *   `--ignore-shutdown`, `--crash-like-symfony` (crashes at start like phpactor: an error block, then the usage of
 *   its command), `--stop-reading-on-change <text>` (hangs, alive but no longer reading its stdin, once a
 *   `didChange` inserts that text);
 * - output: `--garbage-stdout`, `--huge-message <mb>` (the hover answer), `--stderr-flood`;
 * - timing and questions: `--latency-ms <n>`, `--progress-on-references <ms>` (each search of references takes
 *   that long, within a "Finding references" progress, like phpactor), `--ask-configuration` (asks `workspace/configuration` after
 *   `initialized` and writes the answer to stderr), `--diagnostics`, `--progress <seconds>` (reports an
 *   "Indexing" progress from 0 to 100 % over that many seconds after `initialized`), `--blind-until-indexed` (with
 *   `--progress`: like intelephense, finds no definition nor usage until its indexing ended, which begins a moment
 *   after `initialized`), `--indexing-starts-after <ms>` (that moment, 200 ms by default: later than the IDE waits
 *   for an indexing to begin, it counts usages before, like a server slowed down by a loaded machine);
 * - completion: `--only-items a,b` (proposes only these, like a server that knows one thing), `--completion-items <n>` (n more functions, `generated_00001`...), `--full-sync` (asks for the whole
 *   text at each change), `--no-completion` (answers completion with an error), `--hang-completion` (never answers it), `--no-detail` (items without `detail`, which resolving adds, as
 *   typescript-language-server does).
 * A crash writes `Fatal error: fake crash` on stderr first, like a real server would.
 * Memory is allocated as direct buffers with one byte written per page, so that it becomes resident.
 */
object FakeLanguageServer {
    private const val MB = 1024 * 1024
    private const val PAGE = 4096
    private const val CHUNK_MB = 4
    private const val CRASH_EXIT = 3
    private const val PROGRESS_STEPS = 10
    private const val INDEXING_START_MS = 200L
    private const val PERCENT = 100
    private const val TICK_MS = 100L
    private const val MILLIS_PER_SECOND = 1000L
    private const val WATCHED_DELETED = 3
    private const val SYMFONY_WIDTH = 66
    private const val SYMFONY_EXIT = 255

    /** Starts the server (or, with `--hold-mb n`, a child that only holds n MB until its stdin closes). */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        if (options.holdMb != null) {
            hold(options.holdMb)
            return
        }
        if (options.crashOnStart) crash()
        if (options.crashLikeSymfony) crashLikeSymfony()
        Server(options, System.`in`, System.out, System.err).run()
    }

    private fun hold(megabytes: Int) {
        val held = Memory()
        held.resizeTo(megabytes)
        System.`in`.read()
        held.resizeTo(0)
    }

    private fun crash(): Nothing {
        System.err.println("Fatal error: fake crash")
        System.err.flush()
        exitProcess(CRASH_EXIT)
    }

    // What a Symfony Console command prints when it throws: the error block, padded, then the usage of the command.
    private fun crashLikeSymfony(): Nothing {
        val padding = " ".repeat(SYMFONY_WIDTH)
        listOf(
            "Starting language server, use -vvv for verbose output",
            "In ArgumentAssert.php line 52:",
            padding,
            "  Argument \"disabledSupport\" has type \"bool\" but was passed \"array\"  ",
            padding,
            "language-server [--address ADDRESS] [--no-loop]",
        ).forEach(System.err::println)
        System.err.flush()
        exitProcess(SYMFONY_EXIT)
    }

    /** The options of the command line. */
    private class Options(
        val leakMbPerSecond: Int = 0,
        val sawtooth: List<Int>? = null,
        val spikeThenPlateau: List<Int>? = null,
        val childMb: Int? = null,
        val holdMb: Int? = null,
        val crashOnStart: Boolean = false,
        val crashLikeSymfony: Boolean = false,
        val crashAfter: Int? = null,
        val hangOnInitialize: Boolean = false,
        val ignoreShutdown: Boolean = false,
        val garbageStdout: Boolean = false,
        val hugeMessageMb: Int? = null,
        val stderrFlood: Boolean = false,
        val latencyMs: Long = 0,
        val progressOnReferencesMs: Long? = null,
        val askConfiguration: Boolean = false,
        val diagnostics: Boolean = false,
        val progressSeconds: Int? = null,
        val completionItems: Int = 0,
        val onlyItems: List<String>? = null,
        val fullSync: Boolean = false,
        val noCompletion: Boolean = false,
        val hangCompletion: Boolean = false,
        val noDetail: Boolean = false,
        val without: Set<String> = emptySet(),
        val noDiagnostics: Boolean = false,
        // Gives the diagnostics when asked (textDocument/diagnostic) instead of publishing them.
        val pullDiagnostics: Boolean = false,
        // Reads the files on disk once, like a server that indexes them, then only what didChangeWatchedFiles names.
        val index: Boolean = false,
        // With progressSeconds: no definition nor usage until the indexing ended, as intelephense answers.
        val blindUntilIndexed: Boolean = false,
        // With blindUntilIndexed: how long after initialized the indexing begins.
        val indexingStartMs: Long = INDEXING_START_MS,
        // Hangs without reading stdin any more once a didChange carries this text.
        val stopReadingOnChange: String? = null,
    ) {
        val allocates get() = leakMbPerSecond > 0 || sawtooth != null || spikeThenPlateau != null

        companion object {
            fun parse(args: Array<String>): Options {
                val values = mutableMapOf<String, String>()
                var index = 0
                while (index < args.size) {
                    val name = args[index++].removePrefix("--")
                    val takesValue = name in WITH_VALUE
                    values[name] = if (takesValue) args[index++] else "true"
                }
                fun int(name: String) = values[name]?.toInt()
                fun triple(name: String) = values[name]?.split(':')?.map(String::toInt)
                return Options(
                    leakMbPerSecond = int("leak-mb-per-s") ?: 0,
                    sawtooth = triple("sawtooth"),
                    spikeThenPlateau = triple("spike-then-plateau"),
                    childMb = int("child-mb"),
                    holdMb = int("hold-mb"),
                    crashOnStart = "crash-on-start" in values,
                    crashLikeSymfony = "crash-like-symfony" in values,
                    crashAfter = int("crash-after"),
                    hangOnInitialize = "hang-on-initialize" in values,
                    ignoreShutdown = "ignore-shutdown" in values,
                    garbageStdout = "garbage-stdout" in values,
                    hugeMessageMb = int("huge-message"),
                    stderrFlood = "stderr-flood" in values,
                    latencyMs = values["latency-ms"]?.toLong() ?: 0,
                    progressOnReferencesMs = values["progress-on-references"]?.toLong(),
                    askConfiguration = "ask-configuration" in values,
                    diagnostics = "diagnostics" in values,
                    progressSeconds = int("progress"),
                    completionItems = int("completion-items") ?: 0,
                    onlyItems = values["only-items"]?.split(','),
                    fullSync = "full-sync" in values,
                    noCompletion = "no-completion" in values,
                    hangCompletion = "hang-completion" in values,
                    noDetail = "no-detail" in values,
                    without = values["without"]?.split(',')?.toSet().orEmpty(),
                    noDiagnostics = "no-diagnostics" in values,
                    pullDiagnostics = "pull-diagnostics" in values,
                    index = "index" in values,
                    blindUntilIndexed = "blind-until-indexed" in values,
                    indexingStartMs = values["indexing-starts-after"]?.toLong() ?: INDEXING_START_MS,
                    stopReadingOnChange = values["stop-reading-on-change"],
                )
            }

            private val WITH_VALUE =
                setOf(
                    "leak-mb-per-s",
                    "sawtooth",
                    "spike-then-plateau",
                    "child-mb",
                    "hold-mb",
                    "crash-after",
                    "huge-message",
                    "latency-ms",
                    "progress-on-references",
                    "progress",
                    "completion-items",
                    "only-items",
                    "without",
                    "indexing-starts-after",
                    "stop-reading-on-change",
                )
        }
    }

    /** Resident memory held by the server, grown or shrunk by chunks. */
    private class Memory {
        private val chunks = ArrayList<ByteBuffer>()

        fun resizeTo(megabytes: Int) {
            val wanted = megabytes / CHUNK_MB
            val shrinks = wanted < chunks.size
            while (chunks.size < wanted) {
                chunks +=
                    ByteBuffer.allocateDirect(CHUNK_MB * MB).also { buffer ->
                        for (offset in 0 until buffer.capacity() step PAGE) buffer.put(offset, 1)
                    }
            }
            while (chunks.size > wanted) chunks.removeLast()
            // Direct buffers are freed by the garbage collector: ask for it, so a sawtooth goes down for real.
            if (shrinks) System.gc()
        }
    }

    private class Server(
        private val options: Options,
        input: InputStream,
        private val output: OutputStream,
        private val log: PrintStream,
    ) {
        private val input = BufferedInputStream(input)
        private val writeLock = Any()
        private var requests = 0
        private var shutdown = false
        private var child: Process? = null
        private val documents = HashMap<String, MutableList<String>>()
        private var rootUri: String? = null

        // Its configuration, from initializationOptions, like phpactor's: the inlay hints, and the types among them.
        // Unlike phpactor, the hints are on when the client says nothing, so that the tests need not configure them.
        private var hintsEnabled = true
        private var typeHints = false

        // The files on disk as indexed with `--index`, by URI.
        private var indexed: MutableMap<String, List<String>>? = null

        // False until the indexing of `--progress` ended, with `--blind-until-indexed`: no definition nor usage.
        @Volatile private var sees = !(options.blindUntilIndexed && options.progressSeconds != null)

        private var searches = 0

        // executeCommand requests waiting for the answer to the applyEdit they sent, by the applyEdit's id.
        private val pendingCommands = HashMap<String, JsonElement>()
        private var nextEditId = 0

        private fun intelligence() = FakeIntelligence(phpCode())

        private fun hierarchy() = FakeHierarchy(phpCode())

        private fun phpCode() = FakePhpCode(documents.mapValues { it.value.toList() }, rootUri, disk())

        private fun disk(): Map<String, List<String>>? =
            if (options.index) indexed ?: FakePhpCode.readDisk(rootUri).toMutableMap().also { indexed = it } else null

        // Updates the index with the files the client says changed: 1 created, 2 changed, 3 deleted.
        private fun watchedFilesChanged(params: JsonElement?) {
            val changes = (params as? JsonObject)?.get("changes") as? JsonArray ?: return
            for (change in changes) {
                val fields = change as? JsonObject ?: continue
                val uri = (fields["uri"] as? JsonPrimitive)?.contentOrNull ?: continue
                val type = (fields["type"] as? JsonPrimitive)?.intOrNull
                log.println("watched $type $uri")
                val index = indexed ?: continue
                val lines = if (type == WATCHED_DELETED) null else FakePhpCode.readFile(uri)
                if (lines == null) index.remove(uri) else index[uri] = lines
            }
            log.flush()
        }

        fun run() {
            if (options.stderrFlood) thread(isDaemon = true, name = "stderr-flood") { flood() }
            if (options.allocates) thread(isDaemon = true, name = "memory") { allocate() }
            options.childMb?.let { child = startChild(it) }
            if (options.garbageStdout) {
                output.write("Starting the fake language server...\n".toByteArray())
                output.flush()
            }
            while (true) {
                val message = read() ?: exit(1)
                handle(message)
            }
        }

        private fun handle(message: JsonObject) {
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            val id = message["id"]
            if (method == null) {
                log.println("response ${message["id"]}: ${message["result"] ?: message["error"]}")
                log.flush()
                val answered = (message["id"] as? JsonPrimitive)?.contentOrNull
                pendingCommands.remove(answered)?.let { command -> reply(command, JsonNull) }
                return
            }
            if (id != null) {
                requests++
                if (options.crashAfter != null && requests > options.crashAfter) crash()
            }
            if (options.latencyMs > 0) Thread.sleep(options.latencyMs)
            when (method) {
                "initialize" -> {
                    rootUri = (message["params"] as? JsonObject)?.get("rootUri")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    configure(message["params"])
                    if (!options.hangOnInitialize) reply(id, capabilities())
                }
                "initialized" -> {
                    if (options.askConfiguration) askConfiguration()
                    options.progressSeconds?.let { seconds -> thread(isDaemon = true) { reportProgress(seconds) } }
                }
                "shutdown" -> if (!options.ignoreShutdown) shutdown().also { reply(id, JsonNull) }
                "exit" -> if (!options.ignoreShutdown) exit(if (shutdown) 0 else 1)
                "textDocument/hover" -> reply(id, hover(message["params"]))
                "textDocument/didOpen" -> {
                    opened(message["params"])
                    if (options.diagnostics) publishDiagnostics(message["params"]) else publishProblems()
                }
                "textDocument/didChange" -> {
                    val marker = options.stopReadingOnChange
                    if (marker != null && marker in message["params"].toString()) stopReading()
                    changed(message["params"])
                    if (!options.diagnostics) publishProblems()
                }
                "textDocument/didClose" -> documents.remove(uriOf(message["params"]))
                "textDocument/didSave" -> log.println("saved ${uriOf(message["params"])}").also { log.flush() }
                "workspace/didChangeWatchedFiles" -> {
                    watchedFilesChanged(message["params"])
                    refreshPulledDiagnostics()
                }
                "textDocument/diagnostic" -> reply(id, diagnosticReport(message["params"]))
                "workspace/willRenameFiles" -> reply(id, FakeFileOperations(phpCode(), intelligence()).willRename(message["params"]))
                "workspace/willCreateFiles", "workspace/willDeleteFiles" -> reply(id, JsonNull)
                "workspace/didCreateFiles", "workspace/didRenameFiles", "workspace/didDeleteFiles" ->
                    log.println("${method.removePrefix("workspace/")} ${message["params"]}").also { log.flush() }
                "textDocument/definition", "textDocument/references" -> if (!sees) reply(id, JsonArray(emptyList())) else answerNavigation(id, method, message["params"])
                "textDocument/declaration" -> reply(id, intelligence().definition(message["params"]))
                "textDocument/typeDefinition" -> reply(id, intelligence().typeDefinition(message["params"]))
                "textDocument/implementation" -> reply(id, intelligence().implementation(message["params"]))
                "textDocument/documentHighlight" -> reply(id, intelligence().highlights(message["params"]))
                "textDocument/documentSymbol" -> reply(id, intelligence().documentSymbols(message["params"]))
                "workspace/symbol" -> reply(id, intelligence().workspaceSymbols(message["params"]))
                "textDocument/selectionRange" -> reply(id, intelligence().selectionRanges(message["params"]))
                "textDocument/foldingRange" -> reply(id, intelligence().foldingRanges(message["params"]))
                "textDocument/inlayHint" -> reply(id, intelligence().inlayHints(message["params"], typeHints))
                "textDocument/codeLens" -> reply(id, FakeCodeLens(phpCode()).lenses(message["params"]))
                "codeLens/resolve" -> reply(id, FakeCodeLens(phpCode()).resolve(message["params"]))
                "textDocument/onTypeFormatting" -> reply(id, FakeTypeFormatting(phpCode()).format(message["params"]))
                "textDocument/documentLink" -> reply(id, FakeDocumentLinks(phpCode()).links(message["params"]))
                "documentLink/resolve" -> reply(id, FakeDocumentLinks(phpCode()).resolve(message["params"]))
                "textDocument/semanticTokens/full" -> reply(id, FakeSemanticTokens(phpCode()).tokens(message["params"]))
                "textDocument/semanticTokens/range" -> reply(id, FakeSemanticTokens(phpCode()).tokens(message["params"]))
                "textDocument/prepareCallHierarchy" -> reply(id, hierarchy().prepareCalls(message["params"]))
                "callHierarchy/incomingCalls" -> reply(id, hierarchy().incomingCalls(message["params"]))
                "callHierarchy/outgoingCalls" -> reply(id, hierarchy().outgoingCalls(message["params"]))
                "textDocument/prepareTypeHierarchy" -> reply(id, hierarchy().prepareTypes(message["params"]))
                "typeHierarchy/supertypes" -> reply(id, hierarchy().supertypes(message["params"]))
                "typeHierarchy/subtypes" -> reply(id, hierarchy().subtypes(message["params"]))
                "textDocument/signatureHelp" -> reply(id, intelligence().signatureHelp(message["params"]))
                "textDocument/prepareRename" -> reply(id, intelligence().prepareRename(message["params"]))
                "textDocument/rename" -> reply(id, intelligence().rename(message["params"]))
                "textDocument/formatting" -> reply(id, intelligence().format(message["params"], range = false))
                "textDocument/rangeFormatting" -> reply(id, intelligence().format(message["params"], range = true))
                "textDocument/codeAction" -> reply(id, intelligence().codeActions(message["params"]))
                "workspace/executeCommand" -> executeCommand(id!!, message["params"])
                "completionItem/resolve" -> reply(id, intelligence().resolveCompletion(message["params"]))
                "textDocument/completion" ->
                    if (options.hangCompletion) {
                        Unit // Alive, but never answers a completion.
                    } else if (options.noCompletion) {
                        error(id!!, CONTENT_MODIFIED, "Content modified")
                    } else {
                        reply(id, completion(message["params"]))
                    }
                else -> if (id != null) error(id, METHOD_NOT_FOUND, "Unknown method $method")
            }
        }

        // Alive, but reads nothing more: what the IDE writes fills the pipe, then its writes block.
        private fun stopReading(): Nothing {
            log.println("stopped reading")
            log.flush()
            while (true) Thread.sleep(Long.MAX_VALUE)
        }

        private fun shutdown() {
            shutdown = true
        }

        private fun exit(code: Int): Nothing {
            child?.destroyForcibly()
            exitProcess(code)
        }

        // `add_docblock` asks the client to apply its edit, and answers once the client did.
        private fun executeCommand(id: JsonElement, params: JsonElement?) {
            val fields = params as? JsonObject
            val name = (fields?.get("command") as? JsonPrimitive)?.contentOrNull
            val edit = if (name == "add_docblock") intelligence().docblockEdit(fields["arguments"] as? JsonArray) else null
            if (edit == null) {
                reply(id, JsonNull)
                return
            }
            val editId = "apply-${++nextEditId}"
            pendingCommands[editId] = id
            send(request(editId, "workspace/applyEdit", edit))
        }

        // A pulled report: unchanged when the text is the one of [previousResultId].
        private fun diagnosticReport(params: JsonElement?): JsonObject {
            val uri = uriOf(params).orEmpty()
            val resultId = documents[uri]?.joinToString("\n").hashCode().toString()
            val previous = ((params as? JsonObject)?.get("previousResultId") as? JsonPrimitive)?.contentOrNull
            log.println("pulled diagnostics of $uri, ${if (previous == resultId) "unchanged" else "full"}")
            log.flush()
            return buildJsonObject {
                put("resultId", resultId)
                if (previous == resultId) {
                    put("kind", "unchanged")
                } else {
                    put("kind", "full")
                    put("items", intelligence().diagnostics(uri))
                }
            }
        }

        // Files changed on disk may declare what was missing: the client pulls the diagnostics again.
        private fun refreshPulledDiagnostics() {
            if (!options.pullDiagnostics) return
            send(request("refresh-${++nextEditId}", "workspace/diagnostic/refresh", JsonObject(emptyMap())))
        }

        private fun publishProblems() {
            if (options.noDiagnostics || options.pullDiagnostics) return
            val intelligence = intelligence()
            for (uri in documents.keys) {
                notify(
                    "textDocument/publishDiagnostics",
                    buildJsonObject {
                        put("uri", uri)
                        put("diagnostics", intelligence.diagnostics(uri))
                    },
                )
            }
        }

        private fun provided(name: String) = name !in options.without

        private fun configure(params: JsonElement?) {
            val configuration = (params as? JsonObject)?.get("initializationOptions") as? JsonObject
            fun flag(name: String) = (configuration?.get("language_server_worse_reflection.inlay_hints.$name") as? JsonPrimitive)?.booleanOrNull
            hintsEnabled = flag("enable") ?: true
            typeHints = flag("types") ?: false
        }

        private fun capabilities() =
            buildJsonObject {
                putJsonObject("capabilities") {
                    putJsonObject("textDocumentSync") {
                        put("openClose", true)
                        put("change", if (options.fullSync) 1 else 2)
                        putJsonObject("save") { put("includeText", false) }
                    }
                    put("hoverProvider", provided("hover"))
                    put("definitionProvider", provided("definition"))
                    put("declarationProvider", provided("declaration"))
                    put("typeDefinitionProvider", provided("typeDefinition"))
                    put("implementationProvider", provided("implementation"))
                    put("referencesProvider", provided("references"))
                    put("documentHighlightProvider", provided("documentHighlight"))
                    put("documentSymbolProvider", provided("documentSymbol"))
                    put("workspaceSymbolProvider", provided("workspaceSymbol"))
                    put("selectionRangeProvider", provided("selectionRange"))
                    put("foldingRangeProvider", provided("foldingRange"))
                    put("inlayHintProvider", provided("inlayHint") && hintsEnabled)
                    put("callHierarchyProvider", provided("callHierarchy"))
                    if (provided("semanticTokens")) put("semanticTokensProvider", fakeSemanticTokensProvider())
                    put("typeHierarchyProvider", provided("typeHierarchy"))
                    put("documentFormattingProvider", provided("formatting"))
                    put("documentRangeFormattingProvider", provided("rangeFormatting"))
                    if (provided("rename")) putJsonObject("renameProvider") { put("prepareProvider", true) }
                    if (provided("signatureHelp")) {
                        putJsonObject("signatureHelpProvider") {
                            put("triggerCharacters", buildJsonArray { add(JsonPrimitive("(")); add(JsonPrimitive(",")) })
                        }
                    }
                    if (provided("codeAction")) putJsonObject("codeActionProvider") { put("resolveProvider", false) }
                    if (options.pullDiagnostics) {
                        putJsonObject("diagnosticProvider") {
                            put("interFileDependencies", true)
                            put("workspaceDiagnostics", false)
                        }
                    }
                    if (provided("codeLens")) putJsonObject("codeLensProvider") { put("resolveProvider", true) }
                    if (provided("onTypeFormatting")) {
                        putJsonObject("documentOnTypeFormattingProvider") {
                            put("firstTriggerCharacter", ";")
                            put("moreTriggerCharacter", buildJsonArray { add(JsonPrimitive("}")) })
                        }
                    }
                    if (provided("documentLink")) putJsonObject("documentLinkProvider") { put("resolveProvider", true) }
                    if (provided("fileOperations")) {
                        putJsonObject("workspace") { put("fileOperations", fakeFileOperationsCapability()) }
                    }
                    putJsonObject("executeCommandProvider") {
                        put("commands", buildJsonArray { add(JsonPrimitive("add_docblock")) })
                    }
                    putJsonObject("completionProvider") {
                        put("triggerCharacters", buildJsonArray { add(JsonPrimitive("$")) })
                        put("resolveProvider", provided("completionResolve"))
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "fake-language-server")
                    put("version", "1.0")
                }
            }

        private fun hover(params: JsonElement?): JsonElement {
            if (options.hugeMessageMb == null && uriOf(params) in documents) return intelligence().hover(params)
            val huge = options.hugeMessageMb
            val text =
                if (huge != null) {
                    "x".repeat(huge * MB)
                } else {
                    val position = params?.jsonObject?.get("position")?.jsonObject
                    "hover at ${position?.get("line")}:${position?.get("character")}"
                }
            return buildJsonObject { put("contents", text) }
        }

        private fun uriOf(params: JsonElement?): String? {
            val paramsObject = params as? JsonObject
            val textDocument = paramsObject?.get("textDocument") as? JsonObject
            val uri = textDocument?.get("uri") as? JsonPrimitive
            return uri?.contentOrNull
        }

        private fun opened(params: JsonElement?) {
            val document = params?.jsonObject?.get("textDocument")?.jsonObject ?: return
            val uri = document["uri"]?.jsonPrimitive?.contentOrNull ?: return
            documents[uri] = document["text"]?.jsonPrimitive?.content.orEmpty().split('\n').toMutableList()
        }

        private fun changed(params: JsonElement?) {
            val lines = documents[uriOf(params)] ?: return
            for (change in params!!.jsonObject["contentChanges"]!!.jsonArray) {
                val fields = change.jsonObject
                val text = fields["text"]!!.jsonPrimitive.content
                val range = fields["range"]?.jsonObject
                if (range == null) {
                    lines.clear()
                    lines.addAll(text.split('\n'))
                    continue
                }
                val (startLine, startColumn) = positionOf(range["start"])
                val (endLine, endColumn) = positionOf(range["end"])
                val replaced = lines[startLine].substring(0, startColumn) + text + lines[endLine].substring(endColumn)
                lines.subList(startLine, endLine + 1).clear()
                lines.addAll(startLine, replaced.split('\n'))
            }
        }

        private fun positionOf(position: JsonElement?): Pair<Int, Int> {
            val fields = position!!.jsonObject
            return fields["line"]!!.jsonPrimitive.int to fields["character"]!!.jsonPrimitive.int
        }

        // What a PHP server would propose, unfiltered: the client side filters by the typed prefix.
        private fun completion(params: JsonElement?): JsonElement {
            val lines = documents[uriOf(params)] ?: return JsonNull
            val (line, column) = positionOf(params!!.jsonObject["position"])
            val text = lines.getOrElse(line) { "" }
            var start = column.coerceAtMost(text.length)
            while (start > 0 && isWordChar(text[start - 1])) start--
            val functions =
                documents.values.flatMap { document ->
                    document.flatMap { FUNCTION.findAll(it).map { match -> match.groupValues[1] to match.value } }
                }
            val variables = lines.flatMap { VARIABLE.findAll(it).map { match -> match.value } }.distinct()
            val generated = (1..options.completionItems).map { "generated_%05d".format(it) to "function generated()" }
            val proposed = options.onlyItems?.map { it to "class" } ?: (BUILT_INS + functions + generated)
            val items =
                proposed.distinctBy { it.first }.map { (name, detail) ->
                    item(name, FUNCTION_KIND, detail, line, start, column)
                } + variables.takeIf { options.onlyItems == null }.orEmpty().filter { it != text.substring(start, column) }.map {
                    item(it, VARIABLE_KIND, "mixed", line, start, column)
                }
            return buildJsonObject {
                put("isIncomplete", false)
                put("items", buildJsonArray { items.forEach(::add) })
            }
        }

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

        private fun item(label: String, kind: Int, detail: String, line: Int, start: Int, end: Int) =
            buildJsonObject {
                put("label", label)
                put("kind", kind)
                if (!options.noDetail) put("detail", detail)
                putJsonObject("textEdit") {
                    putJsonObject("range") {
                        putJsonObject("start") {
                            put("line", line)
                            put("character", start)
                        }
                        putJsonObject("end") {
                            put("line", line)
                            put("character", end)
                        }
                    }
                    put("newText", label)
                }
            }

        private fun publishDiagnostics(params: JsonElement?) {
            val uri = params?.jsonObject?.get("textDocument")?.jsonObject?.get("uri")
            notify(
                "textDocument/publishDiagnostics",
                buildJsonObject {
                    put("uri", uri ?: JsonNull)
                    put(
                        "diagnostics",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    putJsonObject("range") {
                                        putJsonObject("start") {
                                            put("line", 0)
                                            put("character", 0)
                                        }
                                        putJsonObject("end") {
                                            put("line", 0)
                                            put("character", 1)
                                        }
                                    }
                                    put("severity", 1)
                                    put("message", "fake error")
                                },
                            )
                        },
                    )
                },
            )
        }

        private fun answerNavigation(id: JsonElement?, method: String, params: JsonElement?) {
            val references = method == "textDocument/references"
            val searchMs = options.progressOnReferencesMs
            if (references && searchMs != null) {
                answerReferencesWithProgress(id, params, searchMs)
                return
            }
            val answer = if (references) intelligence().references(params) else intelligence().definition(params)
            reply(id, answer)
        }

        // Like phpactor: each search of references is a work-done progress the server creates, begun and ended.
        private fun answerReferencesWithProgress(id: JsonElement?, params: JsonElement?, searchMs: Long) {
            searches++
            val token = "references-$searches"
            send(request("progress-$token", "window/workDoneProgress/create", buildJsonObject { put("token", token) }))
            progressOf(token, buildJsonObject {
                put("kind", "begin")
                put("title", "Finding references")
            })
            Thread.sleep(searchMs)
            reply(id, intelligence().references(params))
            progressOf(token, buildJsonObject { put("kind", "end") })
        }

        private fun progressOf(token: String, value: JsonObject) =
            notify("\$/progress", buildJsonObject {
                put("token", token)
                put("value", value)
            })

        private fun reportProgress(seconds: Int) {
            // A blind server begins to index a moment after initialized, as intelephense once its configuration came.
            if (!sees) Thread.sleep(options.indexingStartMs)
            send(request("progress-1", "window/workDoneProgress/create", buildJsonObject { put("token", "index") }))
            val steps = PROGRESS_STEPS
            for (step in 0..steps) {
                val kind = if (step == 0) "begin" else "report"
                notify(
                    "\$/progress",
                    buildJsonObject {
                        put("token", "index")
                        put(
                            "value",
                            buildJsonObject {
                                put("kind", kind)
                                if (step == 0) put("title", "Indexing")
                                put("percentage", step * PERCENT / steps)
                            },
                        )
                    },
                )
                Thread.sleep(seconds * MILLIS_PER_SECOND / steps)
            }
            // Like intelephense, it sees the project before it says the indexing ended: a count asked then finds it.
            sees = true
            notify("\$/progress", buildJsonObject {
                put("token", "index")
                put("value", buildJsonObject { put("kind", "end") })
            })
        }

        private fun request(id: String, method: String, params: JsonObject) =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }

        private fun askConfiguration() =
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "configuration-1")
                    put("method", "workspace/configuration")
                    put(
                        "params",
                        buildJsonObject {
                            put("items", buildJsonArray { add(buildJsonObject { put("section", "php") }) })
                        },
                    )
                },
            )

        private fun reply(id: JsonElement?, result: JsonElement) =
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    put("result", result)
                },
            )

        private fun error(id: JsonElement, code: Int, message: String) =
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    putJsonObject("error") {
                        put("code", code)
                        put("message", message)
                    }
                },
            )

        private fun notify(method: String, params: JsonElement) =
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                },
            )

        private fun send(message: JsonObject) {
            val body = message.toString().encodeToByteArray()
            synchronized(writeLock) {
                output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        // Minimal framing: the client under test is the one whose framing is checked.
        private fun read(): JsonObject? {
            var length = -1
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            val body = input.readNBytes(length)
            return if (body.size < length) null else Json.parseToJsonElement(body.decodeToString()).jsonObject
        }

        private fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) return line.toString().trimEnd('\r')
                line.append(byte.toChar())
            }
        }

        private fun flood() {
            val line = "fake language server log line ".repeat(4)
            while (true) log.println(line)
        }

        private fun allocate() {
            val memory = Memory()
            val started = System.currentTimeMillis()
            while (true) {
                val seconds = (System.currentTimeMillis() - started) / MILLIS_PER_SECOND.toDouble()
                memory.resizeTo(targetMb(seconds))
                Thread.sleep(TICK_MS)
            }
        }

        private fun targetMb(seconds: Double): Int {
            val sawtooth = options.sawtooth
            val spike = options.spikeThenPlateau
            return when {
                sawtooth != null -> {
                    val (low, high, period) = sawtooth
                    low + ((high - low) * (seconds % period / period)).toInt()
                }

                spike != null -> {
                    val (spikeMb, plateauMb, spikeSeconds) = spike
                    if (seconds < spikeSeconds) spikeMb else plateauMb
                }

                else -> {
                    (options.leakMbPerSecond * seconds).toInt()
                }
            }
        }

        private fun startChild(megabytes: Int): Process {
            val command = FakeLanguageServerCommand.command("--hold-mb", megabytes.toString())
            return ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
    }

    private const val METHOD_NOT_FOUND = -32601
    private const val CONTENT_MODIFIED = -32801
    private const val FUNCTION_KIND = 3
    private const val VARIABLE_KIND = 6
    private val FUNCTION = Regex("""function\s+(\w+)\s*\([^)]*\)""")
    private val VARIABLE = Regex("""\$\w+""")
    private val BUILT_INS =
        listOf(
            "strlen" to "function strlen(string \$string): int",
            "strtoupper" to "function strtoupper(string \$string): string",
            "str_replace" to "function str_replace(\$search, \$replace, \$subject): string",
            "array_map" to "function array_map(?callable \$callback, array \$array): array",
        )
}
