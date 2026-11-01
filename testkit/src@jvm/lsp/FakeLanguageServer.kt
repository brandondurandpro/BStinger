package org.example.kotlineditor.testkit.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * It answers `initialize` (incremental sync, hover), `shutdown` and `exit` (exit code 0 after shutdown, 1 otherwise,
 * 1 too when stdin closes), echoes the position of `textDocument/hover`, and publishes one diagnostic per opened
 * document with `--diagnostics`. Options, combinable:
 * - memory: `--leak-mb-per-s <n>`, `--sawtooth <lowMb>:<highMb>:<periodS>`,
 *   `--spike-then-plateau <spikeMb>:<plateauMb>:<spikeS>`, `--child-mb <n>` (a child process holding n MB);
 * - lifecycle: `--crash-on-start`, `--crash-after <n requests>` (exit code 3), `--hang-on-initialize`,
 *   `--ignore-shutdown`;
 * - output: `--garbage-stdout`, `--huge-message <mb>` (the hover answer), `--stderr-flood`;
 * - timing and questions: `--latency-ms <n>`, `--ask-configuration` (asks `workspace/configuration` after
 *   `initialized` and writes the answer to stderr), `--diagnostics`.
 * Memory is allocated as direct buffers with one byte written per page, so that it becomes resident.
 */
object FakeLanguageServer {
    private const val MB = 1024 * 1024
    private const val PAGE = 4096
    private const val CHUNK_MB = 4
    private const val CRASH_EXIT = 3
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
        if (options.crashOnStart) exitProcess(CRASH_EXIT)
        Server(options, System.`in`, System.out, System.err).run()
    }

    private fun hold(megabytes: Int) {
        val held = Memory()
        held.resizeTo(megabytes)
        System.`in`.read()
        held.resizeTo(0)
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
                if (options.crashAfter != null && requests > options.crashAfter) exitProcess(CRASH_EXIT)
            }
            if (options.latencyMs > 0) Thread.sleep(options.latencyMs)
            when (method) {
                "initialize" -> if (!options.hangOnInitialize) reply(id, capabilities())
                "initialized" -> if (options.askConfiguration) askConfiguration()
                "shutdown" -> if (!options.ignoreShutdown) shutdown().also { reply(id, JsonNull) }
                "exit" -> if (!options.ignoreShutdown) exit(if (shutdown) 0 else 1)
                "textDocument/hover" -> reply(id, hover(message["params"]))
                "textDocument/didOpen" -> if (options.diagnostics) publishDiagnostics(message["params"])
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
                    put("textDocumentSync", 2)
                    put("hoverProvider", true)
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
}
