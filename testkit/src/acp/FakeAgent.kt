package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
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
 * - `write <path> <text>`: an edit tool call, a permission request, then the file written through the client
 *   (`lines <n> <path>`: the same, the text being `n` lines);
 * - `plan`: a plan of two steps, updated as they complete;
 * - `slow`: chunks every 50 ms until it is cancelled (`stubborn`: the same, but it never stops);
 * - `delegate <title>`: the launch of a sub-agent (a tool call of kind think) completed with the report
 *   `Report of <title>: done.` and a second line `Second line of the report.`; `delegating <title>`: the same call, still running;
 * - `subagent <title>`: a sub-agent as the Claude adapter reports it: the launch `Task` (kind think), two calls of its
 *   own (`Read file 1`, `Run tests 2`) stamped with the launch, the report handed back (`Report of <title>: handed back.`)
 *   and a harness-like note as the content of the completed launch; `subagent-early <title>`: its calls come before
 *   the launch; `subagent-calls <n> <title>`: `n` calls of its own;
 * - `delegate-via-mcp <member> <task>`: the manager of the IDE's relay calls the tool `delegate` of the `kotlin-editor-team`
 *   server its session was started with (the call shown as `delegate <member>`) and answers with the result; a task
 *   the relay prefixed with the member's instructions (`<prompt>` then `# Task`) is read from its `# Task` part;
 * - `stream <n>`: `n` chunks at once; `tools <n>`: `n` completed tool calls (`tools <n> <lines>`: each with an
 *   output of `lines` more lines, as a file read); `crash`: exits with code 3;
 * - `count`: the number of prompts of its session;
 * - `setup`: the `mcpServers` and the `_meta` its session was started or reopened with, as JSON;
 * - `model`: `Model: <model>.`, the model of its session (`mode`: `Mode: <mode>.`, `write` asks nothing in
 *   `accept-edits`; `effort`: `Effort: <effort>.`); `fallback`: it moves to `fake-fast` itself and says so;
 * - `context`: what the user joined to the prompt ([describeContext]), sent as the agent takes it: images unless
 *   `--no-images`, files with their text (embedded context) unless `--no-embedded`;
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
 * neither lists nor reopens them; `--no-config` offers no settings (no model to choose, [FakeConfig]); `--models <n>`
 * offers `n` more models; it lists its commands ([COMMANDS], typed `/echo hi`), but none with `--no-commands`, and
 * `n` more with `--commands <n>`; `--new-after-list` answers `session/new` only once a
 * `session/list` listed the new session (for half a minute at most).
 */
// ACP speaks UTF-8; System.out writes the console's charset (Windows-1252 on Windows), which would mangle accents.
class FakeAgent(
    private val options: FakeAgentOptions,
    private val out: PrintStream = PrintStream(System.out, true, Charsets.UTF_8),
) {
    private val loginRequired = options.loginRequired

    // The `session/list` answered so far.
    private val lists = java.util.concurrent.atomic.AtomicInteger()

    // True once the user logged in (`authenticate`): a session starts then.
    @Volatile private var loggedIn = false
    private val store = FakeSessions(options.store)
    private val turns = ConcurrentHashMap<String, FakeTurn>()
    private val config = FakeConfig(options.extraModels)

    // What each session was started or reopened with: its `mcpServers` and its `_meta`, as JSON (the `setup` prompt).
    // The `session/new` requests received so far (the `sessions` prompt).
    private val created = java.util.concurrent.atomic.AtomicInteger()

    private val setups = ConcurrentHashMap<String, String>()

    private val writeLock = Any()
    private val ids = AtomicLong()
    private val writes = AtomicLong()
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
                id != null -> answers.remove(id.jsonPrimitive.longOrNull)?.complete(message)
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
                "authenticate" -> authenticated(id, params)
                "session/prompt" -> success(id, prompt(params))
                "session/load" -> reopen(id, params, replay = true)
                "session/resume" -> reopen(id, params, replay = false)
                "session/list" -> success(id, listed(params))
                "session/close" -> success(id, JsonObject(emptyMap()))
                "session/delete" -> deleted(id, params)
                "session/fork" -> forked(id, params)
                "session/set_config_option" -> configured(id, params)
                else -> failure(id, METHOD_NOT_FOUND, "Unknown method $method")
            }
        send(reply)
        // Like Claude, it lists its commands once the client knows the session.
        val opened = method in OPENING && reply["result"] != null
        val session = (reply["result"] as? JsonObject)?.text("sessionId") ?: params.text("sessionId")
        if (opened && session != null) announceCommands(session)
    }

    private fun announceCommands(session: String) {
        if (!options.commands) return
        update(session, "available_commands_update") {
            putJsonArray("availableCommands") {
                COMMANDS.forEach { (name, description, hint) -> add(command(name, description, hint)) }
                repeat(options.extraCommands) { add(command("extra-${it + 1}", "Extra command ${it + 1}", null)) }
            }
        }
    }

    private fun initialized() =
        buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("agentCapabilities") {
                put("loadSession", options.history && options.load)
                putJsonObject("mcpCapabilities") { put("http", true) }
                putJsonObject("promptCapabilities") {
                    put("image", options.images)
                    put("embeddedContext", options.embedded)
                }
                if (options.history) {
                    putJsonObject("sessionCapabilities") {
                        putJsonObject("list") {}
                        putJsonObject("resume") {}
                        putJsonObject("close") {}
                        putJsonObject("delete") {}
                        putJsonObject("fork") {}
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

    private fun authenticated(id: JsonElement, params: JsonObject): JsonObject {
        if (params.text("methodId") != "login") return failure(id, INVALID_PARAMS, "Unknown login method")
        loggedIn = true
        return success(id, JsonObject(emptyMap()))
    }

    private fun newSession(id: JsonElement, params: JsonObject): JsonObject {
        if (loginRequired && !loggedIn) return failure(id, AUTH_REQUIRED, "Authentication required")
        val session = store.create(params.text("cwd").orEmpty())
        created.incrementAndGet()
        setups[session.id] = setupOf(params)
        awaitListed()
        return success(id, sessionAnswer(session, buildJsonObject { put("sessionId", session.id) }))
    }

    private fun setupOf(params: JsonObject): String =
        buildJsonObject {
            put("mcpServers", params["mcpServers"] ?: JsonNull)
            put("_meta", params["_meta"] ?: JsonNull)
        }.toString()

    // `--new-after-list`: the new session is listed (`session/list`) before the client is told its id.
    private fun awaitListed() {
        if (!options.newAfterList) return
        val listedBefore = lists.get()
        val deadline = System.nanoTime() + LISTED_WAIT_NANOS
        while (lists.get() == listedBefore && System.nanoTime() < deadline) Thread.sleep(LISTED_POLL_MS)
    }

    // `session/load` replays the history before it answers; `session/resume` does not.
    private fun reopen(id: JsonElement, params: JsonObject, replay: Boolean): JsonObject {
        val session = params.text("sessionId")?.let(store::get)
        val unsupported = !options.history || (replay && !options.load)
        return when {
            unsupported -> failure(id, METHOD_NOT_FOUND, "Sessions cannot be reopened")
            session == null -> failure(id, RESOURCE_NOT_FOUND, "Unknown session ${params.text("sessionId")}")
            else -> {
                setups[session.id] = setupOf(params)
                if (replay) replayed(session)
                success(id, sessionAnswer(session, JsonObject(emptyMap())))
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
            turn.context.forEach { block ->
                update(session.id, "user_message_chunk") {
                    put("messageId", "user-$index")
                    put("content", block)
                }
            }
            update(session.id, "agent_message_chunk") {
                put("messageId", "agent-$index")
                put("content", textBlock(turn.reply.toString()))
            }
        }
    }

    private fun listed(params: JsonObject): JsonObject {
        val answer = sessionList(params)
        lists.incrementAndGet()
        return answer
    }

    private fun sessionList(params: JsonObject) =
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

    // [answer] with the settings of [session], unless it offers none.
    private fun sessionAnswer(session: FakeSession, answer: JsonObject): JsonObject {
        if (!options.config) return answer
        return JsonObject(answer + ("configOptions" to config.options(session)))
    }

    private fun configured(id: JsonElement, params: JsonObject): JsonObject {
        val session = params.text("sessionId")?.let(store::get)
        val option = params.text("configId").orEmpty()
        val value = params.text("value").orEmpty()
        if (session == null || !config.accepts(session, option, value)) {
            return failure(id, INVALID_PARAMS, "No $option $value")
        }
        config.set(session, option, value)
        store.save(session)
        return success(id, buildJsonObject { put("configOptions", config.options(session)) })
    }

    private fun fellBack(session: String): String {
        val kept = store[session]
        if (kept != null) {
            config.set(kept, FakeConfig.MODEL, FakeConfig.DEFAULT_MODEL)
            update(session, "config_option_update") { put("configOptions", config.options(kept)) }
        }
        message(session, "Fell back to ${FakeConfig.DEFAULT_MODEL}.")
        return END_TURN
    }

    // `session/fork`: a new session with the history of the one asked for.
    private fun forked(id: JsonElement, params: JsonObject): JsonObject {
        val from = params.text("sessionId")?.let(store::get)
            ?: return failure(id, RESOURCE_NOT_FOUND, "Unknown session")
        val copy = store.create(from.cwd)
        copy.title = from.title
        copy.turns.addAll(from.turns)
        store.save(copy)
        return success(id, sessionAnswer(copy, buildJsonObject { put("sessionId", copy.id) }))
    }

    private fun deleted(id: JsonElement, params: JsonObject): JsonObject {
        val session = params.text("sessionId").orEmpty()
        if (!store.delete(session)) return failure(id, RESOURCE_NOT_FOUND, "Unknown session $session")
        return success(id, JsonObject(emptyMap()))
    }

    private fun prompt(params: JsonObject): JsonObject {
        val session = params.text("sessionId").orEmpty()
        cancelled.remove(session)
        val blocks = (params["prompt"] as? JsonArray).orEmpty().map { it.jsonObject }
        // The text typed is the first block; the others are what the user joined to it.
        val prompt = blocks.firstOrNull()?.text("text").orEmpty()
        val context = blocks.drop(1)
        val kept = store[session]
        val turn = FakeTurn(prompt, context = context)
        kept?.turns?.add(turn)
        turns[session] = turn
        val count = kept?.turns?.size ?: 1
        if (kept != null && kept.title == null) titled(kept, prompt)
        try {
            // A session whose first prompt starts with `discuss` leads a discussion: a question, then its plan.
            val leads = kept?.turns?.firstOrNull()?.prompt?.startsWith("discuss") == true
            val stopReason = if (leads) discussing(session, count) else answer(session, prompt, count)
            usage(session, count)
            return buildJsonObject { put("stopReason", stopReason) }
        } finally {
            turns.remove(session)
            kept?.let(store::save)
        }
    }

    // The context used grows with each turn, and the cost with it.
    private fun usage(session: String, turns: Int) {
        update(session, "usage_update") {
            put("used", turns * USAGE_PER_TURN)
            put("size", USAGE_WINDOW)
            putJsonObject("cost") {
                put("amount", turns * COST_PER_TURN)
                put("currency", "USD")
            }
        }
    }

    private fun titled(session: FakeSession, prompt: String) {
        val title = prompt.trim().take(TITLE_LENGTH)
        session.title = title
        update(session.id, "session_info_update") { put("title", title) }
    }

    private fun answer(session: String, prompt: String, count: Int): String {
        // A slash command (`/echo hi`) is the same prompt as its name.
        // A task the IDE's relay prefixed with the instructions of its member (`<prompt>\n\n# Task\n<task>`): the task is the command.
        // A prompt that already starts with a command is that command, as before.
        val head = prompt.substringBefore("\n\n# Task\n").trim().removePrefix("/").substringBefore(' ')
        val task = if (head in COMMAND_WORDS) prompt else prompt.substringAfter("\n\n# Task\n", prompt)
        val typed = task.trim().removePrefix("/")
        val words = typed.split(Regex("\\s+"))
        val rest = typed.substringAfter(' ', "")
        return when (words.firstOrNull()) {
            "echo" -> chunks(session, rest.split(' ').map { "$it " })
            "env" -> chunks(session, listOf("$rest=${System.getenv(rest).orEmpty()}"))
            "think" -> think(session, rest)
            "read" -> read(session, rest)
            "write" -> write(session, words.getOrNull(1).orEmpty(), rest.substringAfter(' ', ""))
            "rewrite" -> rewrite(session, rest)
            "lines" -> write(session, words.getOrNull(2).orEmpty(), linesOf(words.getOrNull(1)?.toIntOrNull() ?: 1))
            "plan" -> plan(session)
            "slow" -> slow(session, stubborn = false)
            "stubborn" -> slow(session, stubborn = true)
            "stream" -> chunks(session, List(words.getOrNull(1)?.toIntOrNull() ?: 1) { "chunk $it " })
            "delegate" -> delegate(session, rest, finish = true)
            "subagent" -> subagent(session, rest, calls = 2, early = false)
            "subagent-early" -> subagent(session, rest, calls = 2, early = true)
            "subagent-calls" -> subagent(session, words.drop(2).joinToString(" "), words.getOrNull(1)?.toIntOrNull() ?: 1, early = false)
            "delegate-via-mcp" -> viaMcp(session, words.getOrNull(1).orEmpty(), typed.substringAfter(' ', "").substringAfter(' ', ""))
            "delegating" -> delegate(session, rest, finish = false)
            "tools" -> tools(session, words.getOrNull(1)?.toIntOrNull() ?: 1, words.getOrNull(2)?.toIntOrNull() ?: 0)
            "crash" -> crash()
            "count" -> chunks(session, listOf("Prompt $count of $session."))
            "sessions" -> chunks(session, listOf("Sessions created: ${created.get()}."))
            "setup" -> chunks(session, listOf(setups[session].orEmpty()))
            "usage" -> chunks(session, listOf("5-hour limit — ${count * QUOTA_STEP}% · Resets 11pm"))
            "run" -> run(session, words.drop(1))
            "markdown" -> chunks(session, MARKDOWN.chunked(MARKDOWN_PIECE))
            "model" -> chunks(session, listOf("Model: ${store[session]?.model}."))
            "mode" -> chunks(session, listOf("Mode: ${store[session]?.mode}."))
            "effort" -> chunks(session, listOf("Effort: ${store[session]?.effort}."))
            "fallback" -> fellBack(session)
            "context" -> chunks(session, listOf(describeContext(turns[session]?.context.orEmpty())))
            else -> chunks(session, listOf("You said: $prompt"))
        }
    }

    private fun discussing(session: String, count: Int): String {
        val move = if (count == 1) "ASK: which storage do we use?" else "FINAL: use sqlite"
        return chunks(session, listOf("I read the code.\n$move"))
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

    // Reads [path] and writes it back as it read it, as an agent that edits a file sends all of it again.
    private fun rewrite(session: String, path: String): String {
        val read =
            runCatching {
                request("fs/read_text_file", buildJsonObject {
                    put("sessionId", session)
                    put("path", path)
                }).jsonObject.text("content").orEmpty()
            }
        val content = read.getOrElse { return chunks(session, listOf("Cannot read: ${it.message}")) }
        return write(session, path, content)
    }

    private fun write(session: String, path: String, text: String): String {
        // One tool call per write, as a real agent: the first is write-1.
        val call = "write-${writes.incrementAndGet()}"
        tool(session, call, "Write $path", "edit", "pending", path)
        val asks = store[session]?.mode != FakeConfig.ACCEPT_EDITS
        if (!asks) return written(session, call, path, text)
        val outcome =
            request(
                "session/request_permission",
                buildJsonObject {
                    put("sessionId", session)
                    putJsonObject("toolCall") { put("toolCallId", call) }
                    putJsonArray("options") {
                        add(option("allow", "Allow", "allow_once"))
                        add(option("reject", "Reject", "reject_once"))
                    }
                },
            ).jsonObject["outcome"]?.jsonObject
        if (outcome?.text("outcome") == "cancelled") return CANCELLED
        if (outcome?.text("optionId") != "allow") {
            toolUpdate(session, call, "failed", "Rejected")
            message(session, "Not written.")
            return END_TURN
        }
        return written(session, call, path, text)
    }

    private fun linesOf(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    private fun written(session: String, call: String, path: String, text: String): String {
        val write =
            runCatching {
                request("fs/write_text_file", buildJsonObject {
                    put("sessionId", session)
                    put("path", path)
                    put("content", text)
                })
            }
        val failure = write.exceptionOrNull()
        if (failure != null) {
            toolUpdate(session, call, "failed", "Not written: ${failure.message}")
            message(session, "Not written: ${failure.message}")
            return END_TURN
        }
        update(session, "tool_call_update") {
            put("toolCallId", call)
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

    // The launch of a sub-agent, as Claude shows it (a tool call of kind think): [finish] gives its report, else it stays running.
    private fun delegate(session: String, title: String, finish: Boolean): String {
        tool(session, "delegate-${title.hashCode()}", title, "think", "in_progress", "/delegate")
        if (finish) toolUpdate(session, "delegate-${title.hashCode()}", "completed", "Report of $title: done.\nSecond line of the report.")
        message(session, if (finish) "Delegated." else "Delegating.")
        return END_TURN
    }

    private val toolUses = AtomicLong()
    private val mcpHttp by lazy { java.net.http.HttpClient.newHttpClient() }

    // The manager of the IDE's relay: it calls the tool `delegate(member, task)` of the MCP server its session was started
    // with (the url and the bearer token it recorded), shows the call as the Claude adapter does, and answers with the result.
    private fun viaMcp(session: String, member: String, task: String): String {
        val server = relayServer(session) ?: return chunks(session, listOf("No kotlin-editor-team server in this session."))
        val callId = "mcp-delegate-${toolUses.incrementAndGet()}"
        update(session, "tool_call") {
            put("toolCallId", callId)
            put("title", "delegate $member")
            put("kind", "other")
            put("status", "pending")
            putJsonArray("content") {}
            claudeMeta("mcp__kotlin-editor-team__delegate")
        }
        val (url, authorization) = server
        mcpPost(url, authorization, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake-agent","version":"1"}}}""")
        val arguments = buildJsonObject { put("member", member); put("task", task) }
        val call =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "delegate")
                    put("arguments", arguments)
                    putJsonObject("_meta") { put("claudecode/toolUseId", callId) }
                }
            }
        val answer = Json.parseToJsonElement(mcpPost(url, authorization, call.toString())).jsonObject
        val result = answer["result"]?.jsonObject
        val failed = result?.get("isError")?.jsonPrimitive?.booleanOrNull == true || result == null
        val text = ((result?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)?.text("text") ?: answer.toString()
        toolUpdate(session, callId, if (failed) "failed" else "completed", text)
        message(session, if (failed) "The delegation failed." else "The member answered.")
        return END_TURN
    }

    // The url and the `Authorization` header of the `kotlin-editor-team` server the session was started with.
    private fun relayServer(session: String): Pair<String, String>? {
        val setup = setups[session]?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        val servers = setup["mcpServers"] as? JsonArray ?: return null
        val server = servers.map { it.jsonObject }.firstOrNull { it.text("name") == "kotlin-editor-team" } ?: return null
        val headers = (server["headers"] as? JsonArray).orEmpty().map { it.jsonObject }
        val authorization = headers.firstOrNull { it.text("name") == "Authorization" }?.text("value").orEmpty()
        return server.text("url").orEmpty() to authorization
    }

    private fun mcpPost(url: String, authorization: String, body: String): String {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        builder.header("Content-Type", "application/json")
        if (authorization.isNotEmpty()) builder.header("Authorization", authorization)
        return mcpHttp.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body()
    }

    // `_meta.claudeCode`, as the Claude adapter adds it: the tool's name, the launch a call belongs to, the report handed back.
    private fun JsonObjectBuilder.claudeMeta(toolName: String, parent: String? = null, report: String? = null) =
        putJsonObject("_meta") {
            putJsonObject("claudeCode") {
                put("toolName", toolName)
                if (parent != null) put("parentToolUseId", parent)
                if (report != null) {
                    putJsonObject("toolResponse") { putJsonObject("handbackReport") { put("text", report) } }
                }
            }
        }

    // A sub-agent launched as the adapter reports it: a call `Task` of kind think, its title and input, [calls] calls of its own
    // (stamped with the launch), the report it hands back, and a harness-like note as the content of the completed launch.
    // [early]: the calls come before the launch is known.
    private fun subagent(session: String, title: String, calls: Int, early: Boolean): String {
        val launch = "subagent-${title.hashCode()}"
        val children = List(calls) { "$launch-call-$it" }
        if (early) children.indices.forEach { subagentCall(session, launch, children[it], it) }
        update(session, "tool_call") {
            put("toolCallId", launch)
            put("name", "Agent")
            putJsonObject("rawInput") {}
            put("status", "pending")
            put("title", "Task")
            put("kind", "think")
            putJsonArray("content") {}
            claudeMeta("Agent")
        }
        update(session, "tool_call_update") {
            put("toolCallId", launch)
            put("title", title)
            putJsonObject("rawInput") { put("description", title) }
            claudeMeta("Agent")
        }
        if (!early) children.indices.forEach { subagentCall(session, launch, children[it], it) }
        update(session, "tool_call_update") {
            put("toolCallId", launch)
            claudeMeta("Agent", report = "Report of $title: handed back.")
        }
        update(session, "tool_call_update") {
            put("toolCallId", launch)
            put("status", "completed")
            putJsonArray("content") {
                add(buildJsonObject { put("type", "content"); put("content", textBlock("agentId: $launch (for resuming)")) })
            }
            claudeMeta("Agent")
        }
        message(session, "Sub-agent done.")
        return END_TURN
    }

    private fun subagentCall(session: String, launch: String, id: String, index: Int) {
        val names = listOf("Read file" to "Read", "Run tests" to "Bash")
        val (title, tool) = names[index % names.size]
        val label = "$title ${index + 1}"
        update(session, "tool_call") {
            put("toolCallId", id)
            put("title", label)
            put("kind", if (tool == "Read") "read" else "execute")
            put("status", "pending")
            putJsonArray("content") {}
            claudeMeta(tool, parent = launch)
        }
        update(session, "tool_call_update") {
            put("toolCallId", id)
            put("status", "completed")
            putJsonArray("content") {
                add(buildJsonObject { put("type", "content"); put("content", textBlock("Output of $label")) })
            }
            claudeMeta(tool, parent = launch)
        }
    }

    // [count] tool calls, each with an output of [outputLines] more lines (a file read, the output of a command).
    private fun tools(session: String, count: Int, outputLines: Int = 0): String {
        val body = (1..outputLines).joinToString("") { "\nline $it of the output of the tool, as long as a line of code" }
        repeat(count) { index ->
            tool(session, "tool-$index", "Search step $index", "search", "pending", "/step/$index")
            toolUpdate(session, "tool-$index", "completed", "$index matches$body")
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
        val reply = answer.get(CLIENT_TIMEOUT_S, TimeUnit.SECONDS).jsonObject
        val error = reply["error"]?.jsonObject
        if (error != null) throw IOException(error.text("message") ?: "$method failed")
        return reply["result"] ?: JsonNull
    }

    private fun send(message: JsonObject) =
        synchronized(writeLock) {
            out.println(message.toString())
            out.flush()
        }

    private companion object {
        // The first words the fake agent understands as commands.
        private val COMMAND_WORDS = setOf(
            "echo", "think", "read", "write", "rewrite", "lines", "plan", "slow", "stubborn", "stream", "tools", "crash",
            "count", "usage", "run", "markdown", "model", "mode", "effort", "fallback", "context", "setup", "delegate",
            "delegating", "delegate-via-mcp", "subagent", "subagent-early", "subagent-calls", "env",
        )
        const val END_TURN = "end_turn"
        const val CANCELLED = "cancelled"
        const val METHOD_NOT_FOUND = -32_601
        const val USAGE_PER_TURN = 1_000
        const val USAGE_WINDOW = 200_000
        const val COST_PER_TURN = 0.01
        const val INVALID_PARAMS = -32_602
        const val AUTH_REQUIRED = -32_000
        const val RESOURCE_NOT_FOUND = -32_002
        const val TITLE_LENGTH = 30
        const val MARKDOWN_PIECE = 7
        const val QUOTA_STEP = 10
        const val LISTED_POLL_MS = 20L
        const val LISTED_WAIT_NANOS = 30_000_000_000L
        val OPENING = setOf("session/new", "session/load", "session/resume")

        // Its commands: name, description, hint of what to type after it.
        val COMMANDS =
            listOf(
                Triple("echo", "Answers the text that follows", "text to echo"),
                Triple("count", "Counts the prompts of the session", null),
                Triple("model", "Says the model of the session", null),
                Triple("plan", "Makes a plan of two steps", null),
                Triple("usage", "Says the quota used, as Claude does", null),
            )
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

        fun command(name: String, description: String, hint: String?) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                if (hint != null) putJsonObject("input") { put("hint", hint) }
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
    val config: Boolean = true,
    val extraModels: Int = 0,
    val commands: Boolean = true,
    val extraCommands: Int = 0,
    val images: Boolean = true,
    val embedded: Boolean = true,
    val newAfterList: Boolean = false,
) {
    companion object {

        /**
         * Reads `--login`, `--store <dir>`, `--no-load`, `--no-history`, `--no-config`, `--models <n>`,
         * `--no-commands`, `--commands <n>`, `--no-images`, `--no-embedded` and `--new-after-list`.
         */
        fun parse(args: List<String>): FakeAgentOptions {
            val store = valueOf(args, "--store")
            val models = valueOf(args, "--models")?.toIntOrNull() ?: 0
            val commands = valueOf(args, "--commands")?.toIntOrNull() ?: 0
            return FakeAgentOptions(
                loginRequired = "--login" in args,
                store = store?.let { java.io.File(it) },
                history = "--no-history" !in args,
                load = "--no-load" !in args,
                config = "--no-config" !in args,
                extraModels = models,
                commands = "--no-commands" !in args,
                extraCommands = commands,
                images = "--no-images" !in args,
                embedded = "--no-embedded" !in args,
                newAfterList = "--new-after-list" in args,
            )
        }

        private fun valueOf(args: List<String>, option: String): String? =
            args.indexOf(option).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    }
}

/** Command lines that start a [FakeAgent] in a new JVM, with the classpath of the running tests. */
object FakeAgentCommand {
    // One argument file per options line, built once: a command built twice (the IDE rebuilds the agent of a
    // conversation for every request it sends) is the same command line twice, and compares equal.
    private val built = ConcurrentHashMap<String, List<String>>()

    /** `java @argfile FakeAgent <options>`; the classpath goes through an argument file, too long for Windows. */
    fun command(vararg options: String): List<String> {
        val key = options.joinToString("\u0000")
        return built.computeIfAbsent(key) { lineOf(*options) }
    }

    private fun lineOf(vararg options: String): List<String> {
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
