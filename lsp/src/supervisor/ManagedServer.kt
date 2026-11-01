package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.DocumentOutbox
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.documents.SyncKind
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.leak.LeakReport
import org.example.kotlineditor.lsp.leak.Verdict
import org.example.kotlineditor.lsp.memory.MemoryHistory
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.MemoryUsage
import org.example.kotlineditor.model.TextChange
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a [ManagedServer] is asked to do; its coroutine handles them one at a time, in order. */
internal sealed interface Command {
    /** A user appeared: start unless stopped by the user. */
    data object Wanted : Command

    /** The last user left: stop after the idle delay. */
    data object Unused : Command

    /** The user's Start. */
    data object Start : Command

    /** The user's Stop. */
    data object Stop : Command

    /** The user's Restart of [generation]. */
    data class Restart(val generation: Long) : Command

    /** The process exited by itself. */
    class Exited(val process: Process, val code: Int) : Command

    /** The process runs but does not work (no answer to `initialize`). */
    class Failed(val process: Process, val reason: String) : Command

    /** The wait after a crash is over. */
    data class Retry(val generation: Long) : Command

    /** Nobody came back during the idle delay. */
    data object IdleTimeout : Command

    /** Not needed any more (another server was chosen for its language): stop now if nobody uses it. */
    data object Discard : Command

    /** The IDE server stops. */
    class Close(val done: CompletableDeferred<Unit>) : Command
}

private class Progress(val title: String?, val percent: Int?)

/** A server that cannot run here, and why. */
private class Unavailable(override val message: String, cause: Throwable) : Exception(message, cause)

/** One language server across its restarts, driven by one coroutine. */
@Suppress("LongParameterList", "TooManyFunctions")
internal class ManagedServer(
    private val target: ServerTarget,
    private val scope: CoroutineScope,
    private val sampler: ProcessMemorySampler?,
    private val reaper: OrphanReaper?,
    private val timings: SupervisorTimings,
    private val clock: () -> Long,
    // Removes this server from the supervisor if it still has no user; false when one came back meanwhile.
    private val onForgotten: (ManagedServer) -> Boolean,
    private val onChange: () -> Unit,
    // The documents open in the IDE: those under its root are opened in each process it starts.
    private val documents: OpenDocuments? = null,
) {
    val id = target.id
    val language get() = target.spec.language
    val name get() = target.spec.name
    val root get() = target.root

    /** Sessions using it; guarded by the supervisor's lock. */
    val users = HashSet<String>()

    val log = LogRing()

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val progress = ConcurrentHashMap<String, Progress>()
    private val crashes = ArrayDeque<Long>()
    private val stateLock = Any()

    @Volatile private var state = LanguageServerState.Stopped

    @Volatile private var message: String? = null

    @Volatile private var generation = 0L

    @Volatile private var process: Process? = null

    @Volatile private var connection: LspConnection? = null

    // The documents are sent through it once the process answered initialize; null otherwise.
    @Volatile private var outbox: DocumentOutbox? = null

    @Volatile private var leak: LeakReport? = null

    @Volatile var history: MemoryHistory? = null
        private set

    @Volatile var startedAt = 0L
        private set

    @Volatile var indexingEndedAt: Long? = null
        private set

    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    private var userStopped = false
    private var pendingIdle = false
    private var startJob: Job? = null
    private var waitJob: Job? = null
    private var samplerKey: String? = null

    init {
        scope.launch { for (command in commands) handle(command) }
    }

    fun send(command: Command) {
        commands.trySend(command)
    }

    fun info(): LanguageServerInfo {
        val running = process != null && state != LanguageServerState.Starting
        val samples = history?.takeIf { running }
        val latest = samples?.latest
        val current = progress.values.firstOrNull()
        val report = leak
        return LanguageServerInfo(
            id = id,
            serverName = target.spec.name,
            language = target.spec.language,
            languageName = Languages.nameOf(target.spec.language),
            root = target.root,
            state = state,
            generation = generation,
            pid = pid,
            memory = latest?.let { MemoryUsage(it.privateBytes, it.residentBytes, it.processCount) },
            peakPrivateBytes = samples?.peak?.privateBytes ?: 0L,
            progressPercent = current?.percent,
            progressTitle = current?.title,
            leakVerdict = (report?.verdict ?: Verdict.NotEnoughData).name,
            leakReason = report?.reason.orEmpty(),
            message = message,
            recentCrashes = synchronized(crashes) { crashes.size },
            users = users.size,
        )
    }

    fun setLeak(report: LeakReport): Boolean {
        val changed = leak?.verdict != report.verdict
        leak = report
        return changed
    }

    /** Sends [document] to the process, if it is ready; called under the lock of [OpenDocuments]. */
    fun didOpen(document: MirroredDocument) {
        outbox?.didOpen(document)
    }

    /** Sends [changes] of [document], if the process is ready; called under the lock of [OpenDocuments]. */
    fun didChange(document: MirroredDocument, changes: List<TextChange>) {
        outbox?.didChange(document, changes)
    }

    /** Closes [document] in the process, if it is ready; called under the lock of [OpenDocuments]. */
    fun didClose(document: MirroredDocument) {
        outbox?.didClose(document)
    }

    /**
     * Sends [method] once the document notifications queued before were written, and returns its answer; null
     * when no process is ready. Fails like [LspConnection.request] when the process stops meanwhile.
     */
    suspend fun request(method: String, params: JsonElement): JsonElement? {
        val box = outbox
        val link = connection
        if (box == null || link == null) return null
        box.flush()
        return link.request(method, params)
    }

    /** Kills what is left without asking (after [Command.Close], or when it did not answer in time). */
    fun kill() {
        process?.let { OrphanReaper.killTree(it.toHandle()) }
    }

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Wanted -> onWanted()
            Command.Unused -> onUnused()
            Command.IdleTimeout -> retire(force = false)
            Command.Discard -> retire(force = true)
            Command.Start -> startAgain()
            Command.Stop -> stopByUser()
            is Command.Restart -> restart(command.generation)
            is Command.Exited -> ended(command.process, "Exited with code ${command.code}")
            is Command.Failed -> ended(command.process, command.reason)
            is Command.Retry -> retry(command.generation)
            is Command.Close -> close(command.done)
        }
    }

    // A late report about a process already replaced is ignored.
    private suspend fun ended(ended: Process, reason: String) {
        if (ended === process) crashed(reason)
    }

    // Asked for another process than the current one: that restart already happened.
    private suspend fun restart(asked: Long) {
        if (asked == generation) startAgain()
    }

    private fun retry(asked: Long) {
        if (asked == generation && state == LanguageServerState.Restarting) launchStart()
    }

    private fun onWanted() {
        waitJob?.takeIf { it.isActive && pendingIdle }?.cancel()
        pendingIdle = false
        if (!userStopped && process == null && state == LanguageServerState.Stopped) launchStart()
    }

    private fun onUnused() {
        pendingIdle = true
        waitJob?.cancel()
        waitJob =
            scope.launch {
                delay(timings.idleStopMs)
                send(Command.IdleTimeout)
            }
    }

    // Forgotten first: a user arriving from now on gets a new server instead of this closing one.
    private suspend fun retire(force: Boolean) {
        if ((force || pendingIdle) && onForgotten(this)) {
            stopProcess(graceful = true)
            commands.close()
        }
    }

    private suspend fun startAgain() {
        userStopped = false
        synchronized(crashes) { crashes.clear() }
        stopProcess(graceful = true)
        launchStart()
    }

    private suspend fun stopByUser() {
        userStopped = true
        stopProcess(graceful = true)
        setState(LanguageServerState.Stopped, "Stopped by the user")
    }

    private suspend fun close(done: CompletableDeferred<Unit>) {
        stopProcess(graceful = true)
        setState(LanguageServerState.Stopped, "The IDE server stopped")
        commands.close()
        done.complete(Unit)
    }

    private suspend fun crashed(reason: String) {
        stopProcess(graceful = false)
        val now = clock()
        val count =
            synchronized(crashes) {
                crashes.addLast(now)
                while (crashes.isNotEmpty() && now - crashes.first() > timings.crashWindowMs) crashes.removeFirst()
                crashes.size
            }
        val lastLine = log.last()?.let { ": $it" }.orEmpty()
        if (count >= timings.maxCrashes) {
            setState(LanguageServerState.Crashed, "Crashed $count times in a few minutes. $reason$lastLine")
            return
        }
        val wait = timings.firstBackoffMs shl count - 1
        setState(LanguageServerState.Restarting, "$reason$lastLine. Restarting in ${seconds(wait)}")
        val retried = generation
        waitJob?.cancel()
        pendingIdle = false
        waitJob =
            scope.launch {
                delay(wait)
                send(Command.Retry(retried))
            }
    }

    private fun launchStart() {
        waitJob?.cancel()
        generation++
        val started = generation
        setState(LanguageServerState.Starting, null)
        startJob = scope.launch { startProcess(started) }
    }

    // Runs in its own job, so that a stop or a restart can interrupt a slow download or a hung initialize.
    private suspend fun startProcess(started: Long) {
        try {
            val launched = spawn(resolveCommand())
            handshake(launched, connect(launched))
        } catch (unavailable: Unavailable) {
            if (started == generation) setState(LanguageServerState.Unavailable, unavailable.message)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveCommand(): List<String> =
        try {
            withContext(Dispatchers.IO) { target.spec.command() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Any failure of the provisioning (missing runtime, failed download...) is shown, never thrown.
            throw Unavailable(failure.message ?: failure.toString(), failure)
        }

    // Not cancellable: a stop arriving while the process starts must find it in [process] to kill it, else it would
    // run on unseen (withContext drops the result of a block whose caller was cancelled meanwhile).
    private suspend fun spawn(command: List<String>): Process =
        withContext(Dispatchers.IO + NonCancellable) {
            val launched =
                try {
                    ProcessBuilder(command).directory(File(target.root)).start()
                } catch (failure: IOException) {
                    throw Unavailable("Cannot run ${command.first()}: ${failure.message}", failure)
                }
            process = launched
            watch(launched)
            launched
        }

    private fun watch(launched: Process) {
        leak = null
        startedAt = clock()
        indexingEndedAt = null
        progress.clear()
        reaper?.record(launched.toHandle())
        drain(launched.errorStream)
        launched.onExit().thenAccept { send(Command.Exited(it, it.exitValue())) }
    }

    private fun connect(launched: Process): LspConnection =
        LspConnection(
            launched.inputStream,
            launched.outputStream,
            scope,
            onRequest = ::answer,
            onNotification = ::notified,
            onClose = { failure -> failure?.let { log.add("Connection to the server broken: ${it.message}") } },
        ).start().also { connection = it }

    // A process that exits or breaks the protocol during initialize is reported by Exited.
    private suspend fun handshake(launched: Process, link: LspConnection) {
        val timeout = timings.initializeTimeoutMs
        val answered = runCatching { withTimeoutOrNull(timeout) { link.request("initialize", initializeParams()) } }
        val failure = answered.exceptionOrNull()
        when {
            failure is CancellationException -> {
                throw failure
            }

            failure != null -> {
                Unit
            }

            answered.getOrNull() == null -> {
                send(Command.Failed(launched, "No answer to initialize within ${seconds(timeout)}"))
            }

            else -> {
                ready(launched, link, answered.getOrNull())
            }
        }
    }

    private fun ready(launched: Process, link: LspConnection, initialized: JsonElement?) {
        runCatching { link.notify("initialized", JsonObject(emptyMap())) }
        openDocuments(DocumentOutbox(link, scope, syncKindOf(initialized)))
        // A key per process: the old one may still be untracked after a new one started.
        samplerKey = "$id#${launched.pid()}".also { history = sampler?.track(it, launched.toHandle()) }
        setState(if (progress.isEmpty()) LanguageServerState.Running else LanguageServerState.Indexing, null)
    }

    // Under the documents' lock: an edit arriving meanwhile is either replayed here or sent after.
    private fun openDocuments(box: DocumentOutbox) {
        val open = { mirrored: List<MirroredDocument> ->
            outbox = box
            mirrored.forEach(box::didOpen)
        }
        documents?.withDocuments(language, target.root, open) ?: open(emptyList())
    }

    private fun closeOutbox() {
        val close = {
            outbox?.close()
            outbox = null
        }
        documents?.locked(close) ?: close()
    }

    private suspend fun stopProcess(graceful: Boolean) {
        startJob?.cancelAndJoin()
        startJob = null
        closeOutbox()
        val running = process ?: return
        process = null
        val link = connection
        connection = null
        val polite = graceful && link?.isClosed == false
        if (polite && running.isAlive) {
            withTimeoutOrNull(timings.shutdownTimeoutMs) { runCatching { link.request("shutdown") } }
            runCatching { link.notify("exit") }
            withContext(Dispatchers.IO) { running.waitFor(timings.shutdownTimeoutMs, TimeUnit.MILLISECONDS) }
        }
        OrphanReaper.killTree(running.toHandle())
        link?.close()
        samplerKey?.let { sampler?.untrack(it) }
        samplerKey = null
        reaper?.forget(running.pid())
        progress.clear()
    }

    private fun setState(new: LanguageServerState, why: String?) {
        synchronized(stateLock) {
            state = new
            message = why
        }
        onChange()
    }

    private fun progressChanged() {
        val changed =
            synchronized(stateLock) {
                when {
                    state == LanguageServerState.Running && progress.isNotEmpty() -> {
                        state = LanguageServerState.Indexing
                        true
                    }

                    state == LanguageServerState.Indexing && progress.isEmpty() -> {
                        state = LanguageServerState.Running
                        indexingEndedAt = clock()
                        true
                    }

                    else -> {
                        state == LanguageServerState.Indexing
                    }
                }
            }
        if (changed) onChange()
    }

    // The requests a language server sends to its client: answered with what we have, never left waiting.
    private fun answer(method: String, params: JsonElement?): JsonElement =
        when (method) {
            "workspace/configuration" -> {
                val items = (params as? JsonObject)?.get("items")?.jsonArray?.size ?: 0
                JsonArray(List(items) { JsonNull })
            }

            "workspace/workspaceFolders" -> {
                buildJsonArray { add(folder()) }
            }

            "window/workDoneProgress/create", "client/registerCapability", "client/unregisterCapability",
            "window/showMessageRequest", "workspace/semanticTokens/refresh", "workspace/inlayHint/refresh",
            "workspace/diagnostic/refresh", "workspace/codeLens/refresh",
            -> {
                JsonNull
            }

            else -> {
                throw LspResponseException(LspConnection.METHOD_NOT_FOUND, "Unhandled method $method")
            }
        }

    private fun notified(method: String, params: JsonElement?) {
        val fields = params as? JsonObject
        when {
            fields == null -> {
                Unit
            }

            method == "\$/progress" -> {
                onProgress(fields)
            }

            method == "window/logMessage" || method == "window/showMessage" -> {
                (fields["message"] as? JsonPrimitive)?.contentOrNull?.lines()?.forEach(log::add)
            }
        }
    }

    private fun onProgress(fields: JsonObject) {
        val token = (fields["token"] as? JsonPrimitive)?.contentOrNull
        val value = fields["value"] as? JsonObject
        if (token == null || value == null) return
        val percent = (value["percentage"] as? JsonPrimitive)?.intOrNull
        when ((value["kind"] as? JsonPrimitive)?.contentOrNull) {
            "begin" -> progress[token] = Progress((value["title"] as? JsonPrimitive)?.contentOrNull, percent)
            "report" -> progress[token]?.let { progress[token] = Progress(it.title, percent ?: it.percent) }
            "end" -> progress.remove(token)
        }
        progressChanged()
    }

    // Always read: a server whose stderr pipe is full blocks.
    private fun drain(stderr: InputStream) {
        thread(name = "lsp-stderr-$id", isDaemon = true) {
            val lines = LogLines(log)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                stderr.use { input ->
                    var read = input.read(buffer)
                    while (read >= 0) {
                        lines.feed(buffer, read)
                        read = input.read(buffer)
                    }
                }
            } catch (_: IOException) {
                // The process is gone.
            }
            lines.flush()
        }
    }

    private fun folder() =
        buildJsonObject {
            put("uri", rootUri())
            put("name", Paths.get(target.root).fileName?.toString() ?: target.root)
        }

    private fun rootUri() = Paths.get(target.root).toUri().toString()

    private fun initializeParams(): JsonObject =
        buildJsonObject {
            put("processId", ProcessHandle.current().pid())
            putJsonObject("clientInfo") { put("name", "Kotlin Editor") }
            put("rootUri", rootUri())
            put("rootPath", target.root)
            putJsonArray("workspaceFolders") { add(folder()) }
            putJsonObject("capabilities") {
                putJsonObject("window") { put("workDoneProgress", true) }
                putJsonObject("workspace") {
                    put("configuration", true)
                    put("workspaceFolders", true)
                }
                putJsonObject("textDocument") {
                    putJsonObject("synchronization") { put("dynamicRegistration", false) }
                    putJsonObject("publishDiagnostics") {}
                    putJsonObject("completion") {
                        put("contextSupport", false)
                        putJsonObject("completionItem") {
                            // The editor inserts plain text: no tab stops to fill.
                            put("snippetSupport", false)
                            put("insertReplaceSupport", false)
                        }
                    }
                }
            }
            target.spec.initializationOptions?.let { put("initializationOptions", it) }
        }

    // textDocumentSync is a number or an object whose change is one; Full is 1, Incremental 2, None 0.
    private fun syncKindOf(initialized: JsonElement?): SyncKind {
        val initializeResult = initialized as? JsonObject
        val capabilities = initializeResult?.get("capabilities") as? JsonObject
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

    private fun seconds(millis: Long) = if (millis % MILLIS == 0L) "${millis / MILLIS} s" else "$millis ms"

    private companion object {
        const val MILLIS = 1_000L
        const val FULL_SYNC = 1
    }
}
