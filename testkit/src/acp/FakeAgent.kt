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
 * - `markdown`: an answer in Markdown, streamed in pieces that cut its marks: a heading, bold, a list, code, a table;
 * - `run <n> <code>`: runs [FakeProgram] in a terminal of the client, which prints `n` lines and exits with `code`;
 *   `run forever`: one that never stops, killed when the prompt is cancelled; `run big <n>`: `n` lines, of which
 *   the client keeps 10,000 bytes;
 * - anything else: `You said: <prompt>`.
 *
 * Its sessions keep their history (the prompts and the answers): it lists them (`session/list`), replays them
 * (`session/load`), resumes them (`session/resume`), closes and deletes them, and titles them after their first prompt.
 *
 * Options: `--login` refuses `session/new` until the user logs in; `--store <dir>` keeps the sessions in a directory,
 * so that they outlive the process; `--no-load` does not replay sessions (it still resumes them); `--no-history`
 * neither lists nor reopens them.
 */
class FakeAgent(private val options: FakeAgentOptions, private val out: PrintStream = System.out) {
    private val loginRequired = options.loginRequired
    private val store = FakeSessions(options.store)
    private val turns = ConcurrentHashMap<String, FakeTurn>()

    private val writeLock = Any()
    private val ids = AtomicLong()
    private val answers = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

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
                "session/new" -> newSession(id, params)
                "session/prompt" -> success(id, prompt(params))
                "session/load" -> reopen(id, params, replay = true)
                "session/resume" -> reopen(id, params, replay = false)
                "session/list" -> success(id, listed(params))
                "session/close" -> success(id, JsonObject(emptyMap()))
                "session/delete" -> deleted(id, params)
                else -> failure(id, METHOD_NOT_FOUND, "Unknown method $method")
            }
        send(reply)
    }

    private fun initialized() =
        buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("agentCapabilities") {
                put("loadSession", options.history && options.load)
                if (options.history) {
                    putJsonObject("sessionCapabilities") {
                        putJsonObject("list") {}
                        putJsonObject("resume") {}
                        putJsonObject("close") {}
                        putJsonObject("delete") {}
                    }
                }
            }
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

    private fun newSession(id: JsonElement, params: JsonObject): JsonObject {
        if (loginRequired) return failure(id, AUTH_REQUIRED, "Authentication required")
        val session = store.create(params.text("cwd").orEmpty())
        return success(id, buildJsonObject { put("sessionId", session.id) })
    }

    // `session/load` replays the history before it answers; `session/resume` does not.
    private fun reopen(id: JsonElement, params: JsonObject, replay: Boolean): JsonObject {
        val session = params.text("sessionId")?.let(store::get)
        val unsupported = !options.history || (replay && !options.load)
        return when {
            unsupported -> failure(id, METHOD_NOT_FOUND, "Sessions cannot be reopened")
            session == null -> failure(id, RESOURCE_NOT_FOUND, "Unknown session ${params.text("sessionId")}")
            else -> {
                if (replay) replayed(session)
                success(id, JsonObject(emptyMap()))
            }
        }
    }

    private fun replayed(session: FakeSession) {
        session.title?.let { title -> update(session.id, "session_info_update") { put("title", title) } }
        session.turns.toList().forEachIndexed { index, turn ->
            update(session.id, "user_message_chunk") {
                put("messageId", "user-$index")
                put("content", textBlock(turn.prompt))
            }
            update(session.id, "agent_message_chunk") {
                put("messageId", "agent-$index")
                put("content", textBlock(turn.reply.toString()))
            }
        }
    }

    private fun listed(params: JsonObject) =
        buildJsonObject {
            putJsonArray("sessions") {
                store.list(params.text("cwd")).forEach { session ->
                    add(
                        buildJsonObject {
                            put("sessionId", session.id)
                            put("cwd", session.cwd)
                            put("title", session.title?.let(::JsonPrimitive) ?: JsonNull)
                            put("updatedAt", session.updatedAt)
                        },
                    )
                }
            }
        }

    private fun deleted(id: JsonElement, params: JsonObject): JsonObject {
        val session = params.text("sessionId").orEmpty()
        if (!store.delete(session)) return failure(id, RESOURCE_NOT_FOUND, "Unknown session $session")
        return success(id, JsonObject(emptyMap()))
    }

    private fun prompt(params: JsonObject): JsonObject {
        val session = params.text("sessionId").orEmpty()
        cancelled.remove(session)
        val prompt = (params["prompt"] as? JsonArray).orEmpty().joinToString("") { it.jsonObject.text("text").orEmpty() }
        val kept = store[session]
        val turn = FakeTurn(prompt)
        kept?.turns?.add(turn)
        turns[session] = turn
        val count = kept?.turns?.size ?: 1
        if (kept != null && kept.title == null) titled(kept, prompt)
        try {
            return buildJsonObject { put("stopReason", answer(session, prompt, count)) }
        } finally {
            turns.remove(session)
            kept?.let(store::save)
        }
    }

    private fun titled(session: FakeSession, prompt: String) {
        val title = prompt.trim().take(TITLE_LENGTH)
        session.title = title
        update(session.id, "session_info_update") { put("title", title) }
    }

    private fun answer(session: String, prompt: String, count: Int): String {
        val words = prompt.trim().split(Regex("\\s+"))
        val rest = prompt.trim().substringAfter(' ', "")
        return when (words.firstOrNull()) {
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
            "run" -> run(session, words.drop(1))
            "markdown" -> chunks(session, MARKDOWN.chunked(MARKDOWN_PIECE))
            else -> chunks(session, listOf("You said: $prompt"))
        }
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

    private fun run(session: String, words: List<String>): String {
        val terminals = FakeTerminalRun(session, ::request, ::update) { session in cancelled }
        val (stopReason, summary) = terminals.run(words)
        message(session, summary)
        return stopReason
    }

    private fun crash(): String {
        System.err.println("fake agent crashed")
        System.err.flush()
        exitProcess(CRASH_EXIT_CODE)
    }

    private fun message(session: String, text: String) {
        turns[session]?.reply?.append(text)
        update(session, "agent_message_chunk") { put("content", textBlock(text)) }
    }

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

    private fun update(session: String, kind: String, fields: JsonObjectBuilder.() -> Unit): Unit =
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
        const val RESOURCE_NOT_FOUND = -32_002
        const val TITLE_LENGTH = 30
        const val MARKDOWN_PIECE = 7
        val MARKDOWN =
            listOf(
                "# Summary",
                "",
                "The fix is **done** and `parse()` is *faster*.",
                "",
                "- first point",
                "- second point",
                "",
                "```kotlin",
                "fun answer() = 42",
                "```",
                "",
                "| Name | Value |",
                "|------|-------|",
                "| size | 3     |",
            ).joinToString("\n")

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
        fun main(args: Array<String>) = FakeAgent(FakeAgentOptions.parse(args.toList())).run()
    }
}

/** What a [FakeAgent] does, from its command line options. */
data class FakeAgentOptions(
    val loginRequired: Boolean = false,
    val store: java.io.File? = null,
    val history: Boolean = true,
    val load: Boolean = true,
) {
    companion object {
        /** Reads `--login`, `--store <dir>`, `--no-load` and `--no-history`. */
        fun parse(args: List<String>): FakeAgentOptions {
            val store = args.indexOf("--store").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            return FakeAgentOptions(
                loginRequired = "--login" in args,
                store = store?.let { java.io.File(it) },
                history = "--no-history" !in args,
                load = "--no-load" !in args,
            )
        }
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
