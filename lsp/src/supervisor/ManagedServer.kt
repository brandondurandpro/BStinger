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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.example.kotlineditor.lsp.documents.DocumentOutbox
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.leak.LeakReport
import org.example.kotlineditor.lsp.leak.Verdict
import org.example.kotlineditor.lsp.memory.MemoryHistory
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.MemoryUsage
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.Span
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val NANOS_PER_MILLI = 1_000_000L

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
    // What the server says besides answers: diagnostics, edits it asks for.
    private val hooks: LanguageServerHooks = LanguageServerHooks(),
    // A `tsserver/request` of the server (the Vue server asking the TypeScript one): the supervisor relays it.
    private val relay: (from: ManagedServer, request: JsonElement) -> Unit = { _, _ -> },
) {
    val id = target.id
    val language get() = target.spec.language
    val name get() = target.spec.name
    val root get() = target.root

    /** Sessions using it; guarded by the supervisor's lock. */
    val users = HashSet<String>()

    val log = LogRing()

    // The stderr of the current process, read when it crashes to tell why.
    private val stderr = StderrTail(id, log)

    private val launcher = ProcessLauncher(target)

    private val protocol = ClientProtocol(id, target, hooks)

    // The documents are sent to the process once it answered initialize.
    private val mirror = DocumentMirror(documents, target.spec.language, target.root)

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val progress = ServerProgress()
    private val crashes = ArrayDeque<Long>()
    private val stateLock = Any()

    @Volatile private var state = LanguageServerState.Stopped

    @Volatile private var message: String? = null

    @Volatile private var generation = 0L

    @Volatile private var process: Process? = null

    // Start time of [process], read when it started: its children can only be told apart with it once it exited.
    @Volatile private var processStart = 0L

    @Volatile private var connection: LspConnection? = null

    @Volatile private var leak: LeakReport? = null

    // The capabilities the current process answered to initialize; null while none is ready.
    @Volatile private var capabilities: JsonObject? = null

    @Volatile var history: MemoryHistory? = null
        private set

    @Volatile var startedAt = 0L
        private set

    @Volatile var indexingEndedAt: Long? = null
        private set

    // Whether the process indexes the project, and until when (System.nanoTime) it may still begin to after it started.
    private val indexing = MutableStateFlow(false)

    @Volatile private var indexingMayBeginUntil = 0L

    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    private var userStopped = false
    private var pendingIdle = false
    private var startJob: Job? = null

    @Volatile private var startSpan: Span? = null
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
        val current = progress.current()
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
            inlayHintsNote = inlayHintsNote(),
        )
    }

    // Null while no process is ready, or when it gives inlay hints.
    private fun inlayHintsNote(): String? {
        val withoutHints = supports("inlayHintProvider") == false
        val reason = target.spec.withoutInlayHints ?: "${target.spec.name} gives no inlay hints."
        return reason.takeIf { withoutHints }
    }

    fun setLeak(report: LeakReport): Boolean {
        val changed = leak?.verdict != report.verdict
        leak = report
        return changed
    }

    /** Sends [document] to the process, if it is ready; called under the lock of [OpenDocuments]. */
    fun didOpen(document: MirroredDocument) = mirror.didOpen(document)

    /** Sends [changes] of [document], if the process is ready; called under the lock of [OpenDocuments]. */
    fun didChange(document: MirroredDocument, changes: List<TextChange>) = mirror.didChange(document, changes)

    /** Closes [document] in the process, if it is ready; called under the lock of [OpenDocuments]. */
    fun didClose(document: MirroredDocument) = mirror.didClose(document)

    /** Tells the process [document] was saved, if it asked to know (`textDocumentSync.save`). */
    fun didSave(document: MirroredDocument) {
        val sync = capability("textDocumentSync") as? JsonObject
        val save = sync?.get("save")?.takeIf { it != JsonNull }
        val refused = (save as? JsonPrimitive)?.booleanOrNull == false
        if (save != null && !refused) mirror.didSave(document)
    }

    /** Tells the process files changed on disk: their URI and FileChangeType (1 created, 2 changed, 3 deleted). */
    fun filesChanged(changes: List<Pair<String, Int>>) {
        if (changes.isNotEmpty()) mirror.filesChanged(changes)
    }

    /** Tells the process files were created, renamed or deleted through the IDE. */
    fun filesOperated(operation: FileOperation, params: JsonObject) = mirror.filesOperated(operation, params)

    /**
     * Sends [method] once the document notifications queued before were written, and returns its answer; null
     * when no process is ready. Fails like [LspConnection.request] when the process stops meanwhile.
     */
    suspend fun request(method: String, params: JsonElement): JsonElement? {
        val box = mirror.outbox
        val link = connection
        if (box == null || link == null) return null
        box.flush()
        return link.request(method, params)
    }

    /** Sends the notification [method] to the ready process; dropped when none is ready. */
    fun notify(method: String, params: JsonElement) {
        connection?.notify(method, params)
    }

    /**
     * Whether the ready process offers [capability] (`hoverProvider`): present and not false. Null when no process
     * is ready.
     */
    fun supports(capability: String): Boolean? {
        // Absent: not offered.
        val value = capabilities?.let { it[capability] ?: JsonNull }
        val disabled = value is JsonPrimitive && value.booleanOrNull == false
        return value?.let { it != JsonNull && !disabled }
    }

    /** The value of capability [name] of the ready process, null when it has none or no process is ready. */
    fun capability(name: String): JsonElement? = capabilities?.get(name)

    /** Kills what is left without asking (after [Command.Close], or when it did not answer in time). */
    fun kill() {
        val start = processStart
        process?.let { OrphanReaper.killTree(it.toHandle(), start) }
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
        val stopped = process == null && state == LanguageServerState.Stopped
        if (!userStopped && stopped) launchStart()
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
        val retiring = force || pendingIdle
        if (retiring && onForgotten(this)) {
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
        val lastLine = stderr.explanation()?.let { ": $it" }.orEmpty()
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
            val launched = spawn(launcher.resolveCommand())
            handshake(launched, connect(launched))
        } catch (unavailable: Unavailable) {
            if (started == generation) setState(LanguageServerState.Unavailable, unavailable.message)
        }
    }

    // Not cancellable: a stop arriving while the process starts must find it in [process] to kill it, else it would
    // run on unseen (withContext drops the result of a block whose caller was cancelled meanwhile).
    private suspend fun spawn(command: List<String>): Process =
        withContext(Dispatchers.IO + NonCancellable) {
            val launched = launcher.start(command)
            processStart = OrphanReaper.startOf(launched.toHandle())
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
        stderr.drain(launched.errorStream)
        launched.onExit().thenAccept { send(Command.Exited(it, it.exitValue())) }
    }

    private fun connect(launched: Process): LspConnection =
        LspConnection(
            launched.inputStream,
            launched.outputStream,
            scope,
            onRequest = protocol::answer,
            onNotification = ::notified,
            onClose = { failure -> failure?.let { log.add("Connection to the server broken: ${it.message}") } },
        ).start().also { connection = it }

    // A process that exits or breaks the protocol during initialize is reported by Exited.
    private suspend fun handshake(launched: Process, link: LspConnection) {
        val timeout = timings.initializeTimeoutMs
        val answered =
            runCatching {
                withTimeoutOrNull(
                    timeout,
                ) { link.request("initialize", protocol.initializeParams()) }
            }
        val failure = answered.exceptionOrNull()
        when {
            failure is CancellationException -> {
                throw failure
            }

            failure != null -> {
                // the link broke: the server exited, and its exit is reported
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
        capabilities = capabilitiesOf(initialized)
        hooks.onRestarted(id)
        runCatching { link.notify("initialized", JsonObject(emptyMap())) }
        val box =
            DocumentOutbox(
                link,
                scope,
                syncKindOf(initialized),
                maxUnreadBytes = timings.maxUnreadBytes,
                onStuck = { unreadBytes -> stuck(launched, unreadBytes) },
            )
        mirror.open(box)
        // A key per process: the old one may still be untracked after a new one started.
        samplerKey = "$id#${launched.pid()}".also { history = sampler?.track(it, launched.toHandle()) }
        indexingMayBeginUntil = System.nanoTime() + timings.indexingStartMs * NANOS_PER_MILLI
        setState(if (progress.isEmpty) LanguageServerState.Running else LanguageServerState.Indexing, null)
    }

    /**
     * Suspends while the server indexes the project, or may still begin to just after it started, at most [maxMs]:
     * until then a server such as intelephense finds no usage (it answers an empty list). False when it still indexes.
     */
    suspend fun awaitIndexed(maxMs: Long): Boolean {
        val done =
            withTimeoutOrNull(maxMs) {
                val beginsWithin = (indexingMayBeginUntil - System.nanoTime()) / NANOS_PER_MILLI
                if (beginsWithin > 0) delay(beginsWithin)
                indexing.first { !it }
            }
        return done != null
    }

    // Called under the documents' lock: only queues the restart, as a crash, whose stop kills the process.
    private fun stuck(launched: Process, unreadBytes: Long) {
        val reason = "Stopped reading its input, ${unreadBytes / BYTES_PER_MEGABYTE} MB left unread"
        log.add(reason)
        send(Command.Failed(launched, reason))
    }

    private fun closeOutbox() {
        capabilities = null
        mirror.close()
    }

    private suspend fun stopProcess(graceful: Boolean) {
        startJob?.cancelAndJoin()
        startJob = null
        closeOutbox()
        val running = process ?: return
        val start = processStart
        process = null
        val link = connection
        connection = null
        val polite = graceful && link?.isClosed == false
        if (polite && running.isAlive) askToExit(link, running)
        OrphanReaper.killTree(running.toHandle(), start)
        // Closing waits for the lock of a write blocked on a process that stopped reading, until the kill breaks
        // the pipe: never on this coroutine, whatever the system takes to do it.
        link?.let { thread(isDaemon = true, name = "lsp-close") { it.close() } }
        samplerKey?.let { sampler?.untrack(it) }
        samplerKey = null
        reaper?.forget(running.pid())
        progress.clear()
    }

    // Asks the process to shut down and exit, then waits for it a while. The messages are written on a thread of
    // their own: a process that stopped reading its input blocks them behind the write of the outbox, under the
    // connection's lock, which no timeout interrupts. That thread is then left blocked until the kill that follows
    // breaks the pipe, and the process is not waited for, since it was not told to exit.
    private suspend fun askToExit(link: LspConnection, running: Process) {
        val asking =
            scope.launch(Dispatchers.IO) {
                withTimeoutOrNull(timings.shutdownTimeoutMs) { runCatching { link.request("shutdown") } }
                runCatching { link.notify("exit") }
            }
        val asked = withTimeoutOrNull(timings.shutdownTimeoutMs + EXIT_WRITE_MS) { asking.join() }
        if (asked == null) {
            log.add("Could not ask it to exit: it does not read its input")
            return
        }
        withContext(Dispatchers.IO) { running.waitFor(timings.shutdownTimeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun setState(new: LanguageServerState, why: String?) {
        synchronized(stateLock) {
            state = new
            message = why
            indexing.value = new == LanguageServerState.Indexing
        }
        measureStart(new)
        onChange()
    }

    // lsp.start: from Starting to the answer of initialize (Running or Indexing), or to the failure.
    private fun measureStart(new: LanguageServerState) {
        val telemetry = hooks.telemetry()
        val pending = startSpan
        if (new == LanguageServerState.Starting) {
            telemetry.end(pending, Outcome.Cancelled)
            startSpan = telemetry.start(Operation.LspStart, Attributes.of(Attribute.Language to target.spec.language))
            return
        }
        if (pending == null) return
        startSpan = null
        telemetry.end(pending, startOutcome(new))
    }

    private fun startOutcome(new: LanguageServerState): Outcome =
        when (new) {
            LanguageServerState.Running, LanguageServerState.Indexing -> Outcome.Ok
            LanguageServerState.Stopped, LanguageServerState.Restarting -> Outcome.Cancelled
            else -> Outcome.Error(new.name)
        }

    private fun progressChanged() {
        val changed =
            synchronized(stateLock) {
                when {
                    state == LanguageServerState.Running && !progress.isEmpty -> {
                        state = LanguageServerState.Indexing
                        indexing.value = true
                        true
                    }

                    state == LanguageServerState.Indexing && progress.isEmpty -> {
                        state = LanguageServerState.Running
                        indexing.value = false
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

    private fun notified(method: String, params: JsonElement?) {
        val fields = params as? JsonObject
        when {
            method == TSSERVER_REQUEST && params != null -> {
                relay(this, params)
            }

            fields == null -> {
                // a notification without parameters: none the IDE follows
            }

            method == "\$/progress" -> {
                if (progress.update(fields)) progressChanged()
            }

            method == "textDocument/publishDiagnostics" -> {
                hooks.onDiagnostics(id, fields)
            }

            method == "window/logMessage" || method == "window/showMessage" -> {
                (fields["message"] as? JsonPrimitive)?.contentOrNull?.lines()?.forEach(log::add)
            }
        }
    }

    private fun seconds(millis: Long) = if (millis % MILLIS == 0L) "${millis / MILLIS} s" else "$millis ms"

    private companion object {
        const val MILLIS = 1_000L

        const val TSSERVER_REQUEST = "tsserver/request"

        const val BYTES_PER_MEGABYTE = 1024L * 1024

        // How long writing exit may take after shutdown went unanswered; within the supervisor's margin to close.
        const val EXIT_WRITE_MS = 500L
    }
}
