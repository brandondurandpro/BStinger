package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val SLOW_CHUNK_MS = 50L
private const val MAX_SLOW_CHUNKS = 2_000
private const val CRASH_EXIT_CODE = 3
private const val CLIENT_TIMEOUT_S = 60L

/**
 * An ACP agent for tests, run in its own JVM by [FakeAgentCommand]: newline-delimited JSON-RPC on stdin and stdout,
 * no model. What it does depends on the first word of the prompt:
 *
 * - `echo <text>`: answers `<text>` in chunks, one per word;
 * - `think <text>`: a thought, then the answer `Done thinking.`;
 * - `read <path>`: a read tool call, the file read through the client, then `Read <n> lines.`;
 * - `write <path> <text>`: an edit tool call, a permission request, then the file written through the client;
 * - `plan`: a plan of two steps, updated as they complete;
 * - `slow`: chunks every 50 ms until it is cancelled (`stubborn`: the same, but it never stops);
 * - `stream <n>`: `n` chunks at once; `tools <n>`: `n` completed tool calls; `crash`: exits with code 3;
 * - `count`: the number of prompts of its session;
 * - anything else: `You said: <prompt>`.
 *
 * Options: `--login` refuses `session/new` until the user logs in.
 */
class FakeAgent(private val loginRequired: Boolean, private val out: PrintStream = System.out) {
    private val writeLock = Any()
    private val ids = AtomicLong()
    private val answers = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val prompts = ConcurrentHashMap<String, Int>()
    private var sessions = 0

    /** Reads the client's messages until stdin ends. */
    fun run() {
        System.`in`.bufferedReader().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            val method = message.text("method")
            val id = message["id"]?.takeUnless { it is JsonNull }
            when {
                method != null && id != null -> thread(isDaemon = true) { answer(id, method, message.params()) }
                method != null -> onNotification(method, message.params())
                id != null -> answers.remove(id.jsonPrimitive.longOrNull)?.complete(message["result"] ?: JsonNull)
            }
        }
    }

    private fun onNotification(method: String, params: JsonObject) {
        if (method == "session/cancel") params.text("sessionId")?.let(cancelled::add)
    }

    private fun answer(id: JsonElement, method: String, params: JsonObject) {
        val reply =
            when (method) {
                "initialize" -> success(id, initialized())
                "session/new" -> newSession(id)
                "session/prompt" -> success(id, prompt(params))
                else -> failure(id, METHOD_NOT_FOUND, "Unknown method $method")
            }
        send(reply)
    }

    private fun initialized() =
        buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("agentCapabilities") { put("loadSession", false) }
            putJsonObject("agentInfo") {
                put("name", "fake-agent")
                put("title", "Fake Agent")
                put("version", "1.0")
            }
            putJsonArray("authMethods") {
                if (loginRequired) {
                    add(
                        buildJsonObject {
                            put("id", "login")
                            put("name", "Fake login")
                            put("description", "Run fake-login in a terminal")
                        },
                    )
                }
            }
        }

    private fun newSession(id: JsonElement): JsonObject {
        if (loginRequired) return failure(id, AUTH_REQUIRED, "Authentication required")
        val session = synchronized(this) { "session-${++sessions}" }
        return success(id, buildJsonObject { put("sessionId", session) })
    }

    private fun prompt(params: JsonObject): JsonObject {
        val session = params.text("sessionId").orEmpty()
        cancelled.remove(session)
        val count = prompts.merge(session, 1, Int::plus) ?: 1
        val prompt = (params["prompt"] as? JsonArray).orEmpty().joinToString("") { it.jsonObject.text("text").orEmpty() }
        val words = prompt.trim().split(Regex("\\s+"))
        val rest = prompt.trim().substringAfter(' ', "")
        val stopReason =
            when (words.firstOrNull()) {
                "echo" -> chunks(session, rest.split(' ').map { "$it " })
                "think" -> think(session, rest)
                "read" -> read(session, rest)
                "write" -> write(session, words.getOrNull(1).orEmpty(), rest.substringAfter(' ', ""))
                "plan" -> plan(session)
                "slow" -> slow(session, stubborn = false)
                "stubborn" -> slow(session, stubborn = true)
                "stream" -> chunks(session, List(words.getOrNull(1)?.toIntOrNull() ?: 1) { "chunk $it " })
                "tools" -> tools(session, words.getOrNull(1)?.toIntOrNull() ?: 1)
                "crash" -> crash()
                "count" -> chunks(session, listOf("Prompt $count of $session."))
                else -> chunks(session, listOf("You said: $prompt"))
            }
        return buildJsonObject { put("stopReason", stopReason) }
    }

    private fun chunks(session: String, texts: List<String>): String {
        texts.forEach { message(session, it) }
        return END_TURN
    }

    private fun think(session: String, text: String): String {
        update(session, "agent_thought_chunk") { put("content", textBlock(text)) }
        message(session, "Done thinking.")
        return END_TURN
    }

    private fun read(session: String, path: String): String {
        tool(session, "read-1", "Read $path", "read", "pending", path)
        val content = request("fs/read_text_file", buildJsonObject {
            put("sessionId", session)
            put("path", path)
        }).jsonObject.text("content").orEmpty()
        val lines = content.lines().size
        toolUpdate(session, "read-1", "completed", "$lines lines")
        message(session, "Read $lines lines.")
        return END_TURN
    }

    private fun write(session: String, path: String, text: String): String {
        tool(session, "write-1", "Write $path", "edit", "pending", path)
        val outcome =
            request(
                "session/request_permission",
                buildJsonObject {
                    put("sessionId", session)
                    putJsonObject("toolCall") { put("toolCallId", "write-1") }
                    putJsonArray("options") {
                        add(option("allow", "Allow", "allow_once"))
                        add(option("reject", "Reject", "reject_once"))
                    }
                },
            ).jsonObject["outcome"]?.jsonObject
        if (outcome?.text("outcome") == "cancelled") return CANCELLED
        if (outcome?.text("optionId") != "allow") {
            toolUpdate(session, "write-1", "failed", "Rejected")
            message(session, "Not written.")
            return END_TURN
        }
        request("fs/write_text_file", buildJsonObject {
            put("sessionId", session)
            put("path", path)
            put("content", text)
        })
        update(session, "tool_call_update") {
            put("toolCallId", "write-1")
            put("status", "completed")
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "diff")
                        put("path", path)
                        put("oldText", JsonNull)
                        put("newText", text)
                    },
                )
            }
        }
        message(session, "Wrote $path.")
        return END_TURN
    }

    private fun plan(session: String): String {
        val steps = listOf("Look around", "Answer")
        for (done in 0..steps.size) {
            update(session, "plan") {
                putJsonArray("entries") {
                    steps.forEachIndexed { index, step ->
                        add(
                            buildJsonObject {
                                put("content", step)
                                put("priority", "medium")
                                put("status", if (index < done) "completed" else "pending")
                            },
                        )
                    }
                }
            }
        }
        message(session, "Plan done.")
        return END_TURN
    }

    private fun slow(session: String, stubborn: Boolean): String {
        repeat(MAX_SLOW_CHUNKS) { index ->
            if (!stubborn && session in cancelled) return CANCELLED
            message(session, "tick $index ")
            Thread.sleep(SLOW_CHUNK_MS)
        }
        return END_TURN
    }

    private fun tools(session: String, count: Int): String {
        repeat(count) { index ->
            tool(session, "tool-$index", "Search step $index", "search", "pending", "/step/$index")
            toolUpdate(session, "tool-$index", "completed", "$index matches")
        }
        message(session, "Searched $count times.")
        return END_TURN
    }

    private fun crash(): String {
        System.err.println("fake agent crashed")
        System.err.flush()
        exitProcess(CRASH_EXIT_CODE)
    }

    private fun message(session: String, text: String) =
        update(session, "agent_message_chunk") { put("content", textBlock(text)) }

    private fun tool(session: String, id: String, title: String, kind: String, status: String, path: String) =
        update(session, "tool_call") {
            put("toolCallId", id)
            put("title", title)
            put("kind", kind)
            put("status", status)
            putJsonArray("locations") { add(buildJsonObject { put("path", path) }) }
        }

    private fun toolUpdate(session: String, id: String, status: String, output: String) =
        update(session, "tool_call_update") {
            put("toolCallId", id)
            put("status", status)
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "content")
                        put("content", textBlock(output))
                    },
                )
            }
        }

    private fun update(session: String, kind: String, fields: JsonObjectBuilder.() -> Unit) =
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "session/update")
                putJsonObject("params") {
                    put("sessionId", session)
                    putJsonObject("update") {
                        put("sessionUpdate", kind)
                        fields()
                    }
                }
            },
        )

    private fun request(method: String, params: JsonObject): JsonElement {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<JsonElement>()
        answers[id] = answer
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        return answer.get(CLIENT_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun send(message: JsonObject) =
        synchronized(writeLock) {
            out.println(message.toString())
            out.flush()
        }

    private companion object {
        const val END_TURN = "end_turn"
        const val CANCELLED = "cancelled"
        const val METHOD_NOT_FOUND = -32_601
        const val AUTH_REQUIRED = -32_000

        fun textBlock(text: String) =
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }

        fun option(id: String, name: String, kind: String) =
            buildJsonObject {
                put("optionId", id)
                put("name", name)
                put("kind", kind)
            }

        fun success(id: JsonElement, result: JsonElement) =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }

        fun failure(id: JsonElement, code: Int, message: String) =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            }

        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

        fun JsonObject.params(): JsonObject = get("params") as? JsonObject ?: JsonObject(emptyMap())

        /** Runs the fake agent on stdin and stdout. */
        @JvmStatic
        fun main(args: Array<String>) = FakeAgent(loginRequired = "--login" in args).run()
    }
}

/** Command lines that start a [FakeAgent] in a new JVM, with the classpath of the running tests. */
object FakeAgentCommand {
    /** `java @argfile FakeAgent <options>`; the classpath goes through an argument file, too long for Windows. */
    fun command(vararg options: String): List<String> {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val classpath = System.getProperty("java.class.path").replace("""\""", """\\""")
        val argFile = Files.createTempFile("fake-agent", ".args")
        argFile.toFile().deleteOnExit()
        Files.write(argFile, listOf("-Xmx256m", "-cp", "\"$classpath\""))
        return listOf(java, "@$argFile", FakeAgent::class.java.name) + options
    }
}
