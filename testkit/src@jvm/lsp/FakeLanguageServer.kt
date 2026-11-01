package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 *   `--ignore-shutdown`;
 * - output: `--garbage-stdout`, `--huge-message <mb>` (the hover answer), `--stderr-flood`;
 * - timing and questions: `--latency-ms <n>`, `--ask-configuration` (asks `workspace/configuration` after
 *   `initialized` and writes the answer to stderr), `--diagnostics`, `--progress <seconds>` (reports an
 *   "Indexing" progress from 0 to 100 % over that many seconds after `initialized`);
 * - completion: `--completion-items <n>` (n more functions, `generated_00001`...), `--full-sync` (asks for the whole
 *   text at each change), `--no-completion` (answers completion with an error).
 * A crash writes `Fatal error: fake crash` on stderr first, like a real server would.
 * Memory is allocated as direct buffers with one byte written per page, so that it becomes resident.
 */
object FakeLanguageServer {
    private const val MB = 1024 * 1024
    private const val PAGE = 4096
    private const val CHUNK_MB = 4
    private const val CRASH_EXIT = 3
    private const val PROGRESS_STEPS = 10
    private const val PERCENT = 100
    private const val TICK_MS = 100L
    private const val MILLIS_PER_SECOND = 1000L

    /** Starts the server (or, with `--hold-mb n`, a child that only holds n MB until its stdin closes). */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        if (options.holdMb != null) {
            hold(options.holdMb)
            return
        }
        if (options.crashOnStart) crash()
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

    /** The options of the command line. */
    private class Options(
        val leakMbPerSecond: Int = 0,
        val sawtooth: List<Int>? = null,
        val spikeThenPlateau: List<Int>? = null,
        val childMb: Int? = null,
        val holdMb: Int? = null,
        val crashOnStart: Boolean = false,
        val crashAfter: Int? = null,
        val hangOnInitialize: Boolean = false,
        val ignoreShutdown: Boolean = false,
        val garbageStdout: Boolean = false,
        val hugeMessageMb: Int? = null,
        val stderrFlood: Boolean = false,
        val latencyMs: Long = 0,
        val askConfiguration: Boolean = false,
        val diagnostics: Boolean = false,
        val progressSeconds: Int? = null,
        val completionItems: Int = 0,
        val fullSync: Boolean = false,
        val noCompletion: Boolean = false,
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
                    crashAfter = int("crash-after"),
                    hangOnInitialize = "hang-on-initialize" in values,
                    ignoreShutdown = "ignore-shutdown" in values,
                    garbageStdout = "garbage-stdout" in values,
                    hugeMessageMb = int("huge-message"),
                    stderrFlood = "stderr-flood" in values,
                    latencyMs = values["latency-ms"]?.toLong() ?: 0,
                    askConfiguration = "ask-configuration" in values,
                    diagnostics = "diagnostics" in values,
                    progressSeconds = int("progress"),
                    completionItems = int("completion-items") ?: 0,
                    fullSync = "full-sync" in values,
                    noCompletion = "no-completion" in values,
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
                    "progress",
                    "completion-items",
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
                return
            }
            if (id != null) {
                requests++
                if (options.crashAfter != null && requests > options.crashAfter) crash()
            }
            if (options.latencyMs > 0) Thread.sleep(options.latencyMs)
            when (method) {
                "initialize" -> if (!options.hangOnInitialize) reply(id, capabilities())
                "initialized" -> {
                    if (options.askConfiguration) askConfiguration()
                    options.progressSeconds?.let { seconds -> thread(isDaemon = true) { reportProgress(seconds) } }
                }
                "shutdown" -> if (!options.ignoreShutdown) shutdown().also { reply(id, JsonNull) }
                "exit" -> if (!options.ignoreShutdown) exit(if (shutdown) 0 else 1)
                "textDocument/hover" -> reply(id, hover(message["params"]))
                "textDocument/didOpen" -> {
                    opened(message["params"])
                    if (options.diagnostics) publishDiagnostics(message["params"])
                }
                "textDocument/didChange" -> changed(message["params"])
                "textDocument/didClose" -> documents.remove(uriOf(message["params"]))
                "textDocument/completion" ->
                    if (options.noCompletion) {
                        error(id!!, CONTENT_MODIFIED, "Content modified")
                    } else {
                        reply(id, completion(message["params"]))
                    }
                else -> if (id != null) error(id, METHOD_NOT_FOUND, "Unknown method $method")
            }
        }

        private fun shutdown() {
            shutdown = true
        }

        private fun exit(code: Int): Nothing {
            child?.destroyForcibly()
            exitProcess(code)
        }

        private fun capabilities() =
            buildJsonObject {
                putJsonObject("capabilities") {
                    put("textDocumentSync", if (options.fullSync) 1 else 2)
                    put("hoverProvider", true)
                    putJsonObject("completionProvider") {
                        put("triggerCharacters", buildJsonArray { add(JsonPrimitive("$")) })
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "fake-language-server")
                    put("version", "1.0")
                }
            }

        private fun hover(params: JsonElement?): JsonElement {
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
            val items =
                (BUILT_INS + functions + generated).distinctBy { it.first }.map { (name, detail) ->
                    item(name, FUNCTION_KIND, detail, line, start, column)
                } + variables.filter { it != text.substring(start, column) }.map {
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
                put("detail", detail)
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

        private fun reportProgress(seconds: Int) {
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
