package org.example.kotlineditor.acp.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.acp.text
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// A minimal MCP server (https://modelcontextprotocol.io, streamable HTTP, answers as plain JSON, never as a stream)
// that gives a manager session one tool, `delegate`.

private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_ALLOWED = 405
private const val HTTP_ERROR = 500
private const val TOKEN_BYTES = 24
private const val MEMBER = "member"
private const val TASK = "task"
private const val TYPE = "type"
private const val STRING = "string"
private const val DESCRIPTION = "description"
private const val DEFAULT_PROTOCOL = "2025-03-26"
private const val PARSE_ERROR = -32_700
private const val METHOD_NOT_FOUND = -32_601
private const val INVALID_PARAMS = -32_602

/** The name of the tool of a manager. */
internal const val DELEGATE_TOOL = "delegate"

/** What a tool answers: [text], and whether the call failed (the manager reads it either way). */
internal class ToolOutcome(val text: String, val isError: Boolean = false)

/** What a registered manager session offers: the members it can delegate to, and what a delegation does. */
internal interface McpTools {
    /** The names of the members of the team, now. */
    fun members(): List<String>

    /** Runs [task] by [member] (one of [members]) and returns its report; [toolUseId] names the call of the manager. */
    suspend fun delegate(member: String, task: String, toolUseId: String?): ToolOutcome
}

/** Where a manager session reaches its tools: the [url] of the endpoint, and its [token] (`Authorization: Bearer`). */
internal class McpAccess(val url: String, val token: String)

/**
 * The MCP server of the IDE's server, on the loopback interface at a random port. Each manager session registers its
 * [McpTools] and gets its own bearer token: a request with another token is refused. Calls run in [scope]; a call
 * the client cancels (`notifications/cancelled`) is cancelled there.
 */
internal class McpEndpoint(private val scope: CoroutineScope) : Closeable {
    private val tools = ConcurrentHashMap<String, McpTools>()
    private val calls = ConcurrentHashMap<String, Deferred<ToolOutcome>>()
    private val random = SecureRandom()
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(
                runnable,
                "mcp-relay",
            ).apply { isDaemon = true }
        }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/", ::serve)
        server.executor = executor
        server.start()
    }

    /** The address of the endpoint. */
    val url: String get() = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}/mcp"

    /** Serves [offered] to a new token. */
    fun register(offered: McpTools): McpAccess {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tools[token] = offered
        return McpAccess(url, token)
    }

    /** Stops serving the manager of [token]. */
    fun unregister(token: String) {
        tools.remove(token)
        calls.keys.filter { it.startsWith("$token#") }.forEach { calls.remove(it)?.cancel() }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        tools.clear()
    }

    private fun serve(exchange: HttpExchange) {
        try {
            exchange.send(handle(exchange))
        } catch (_: IOException) {
            // The client went away before the answer: nothing to tell it.
        } finally {
            exchange.close()
        }
    }

    private fun handle(exchange: HttpExchange): Reply {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val token = header.removePrefix("Bearer ").trim()
        val offered = tools[token]
        val body = exchange.requestBody.readBytes().decodeToString()
        return when {
            exchange.requestMethod != "POST" -> Reply(HTTP_NOT_ALLOWED)
            offered == null -> Reply(HTTP_UNAUTHORIZED)
            else -> process(token, offered, body)
        }
    }

    private fun process(token: String, offered: McpTools, body: String): Reply {
        val message = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val id = message?.get("id")
        return when {
            message == null -> {
                Reply(HTTP_OK, failure(JsonNull, PARSE_ERROR, "Parse error"))
            }

            id == null -> {
                onNotification(token, message)
                Reply(HTTP_ACCEPTED)
            }

            else -> {
                Reply(HTTP_OK, answer(token, offered, id, message))
            }
        }
    }

    // `notifications/cancelled` cancels the call it names; the others (`initialized`…) need no answer.
    private fun onNotification(token: String, message: JsonObject) {
        if (message.text("method") != "notifications/cancelled") return
        val requestId = (message["params"] as? JsonObject)?.get("requestId") ?: return
        calls.remove(callKey(token, requestId))?.cancel()
    }

    private fun answer(token: String, offered: McpTools, id: JsonElement, message: JsonObject): JsonObject {
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (val method = message.text("method")) {
            "initialize" -> success(id, initialized(params))
            "ping" -> success(id, JsonObject(emptyMap()))
            "tools/list" -> success(id, listed(offered))
            "tools/call" -> called(token, offered, id, params)
            else -> failure(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun called(token: String, offered: McpTools, id: JsonElement, params: JsonObject): JsonObject {
        val name = params.text("name")
        if (name != DELEGATE_TOOL) return failure(id, INVALID_PARAMS, "Unknown tool: $name")
        return success(id, toolResult(delegation(callKey(token, id), offered, params)))
    }

    // A call runs in the scope so that a cancellation of the client reaches it; this thread waits for the outcome.
    private fun delegation(key: String, offered: McpTools, params: JsonObject): ToolOutcome {
        val arguments = params["arguments"] as? JsonObject
        val member = arguments?.text(MEMBER).orEmpty()
        val task = arguments?.text(TASK).orEmpty()
        val problem = problemWith(member, task, offered.members())
        if (problem != null) return problem
        val meta = params["_meta"] as? JsonObject
        val call = scope.async { offered.delegate(member, task, meta?.text("claudecode/toolUseId")) }
        calls[key] = call
        return try {
            runBlocking { call.await() }
        } catch (_: CancellationException) {
            ToolOutcome("The delegation was cancelled.", isError = true)
        } finally {
            calls.remove(key)
        }
    }
}

private fun callKey(token: String, requestId: JsonElement) = "$token#$requestId"

/** What the endpoint answers: an HTTP [status] and, for a JSON-RPC message, its [body]. */
private class Reply(val status: Int, val body: JsonObject? = null)

private fun HttpExchange.send(reply: Reply) {
    val bytes = reply.body?.toString()?.encodeToByteArray()
    if (bytes != null) responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(reply.status, if (bytes == null) -1 else bytes.size.toLong())
    if (bytes != null) responseBody.write(bytes)
}

private fun initialized(params: JsonObject): JsonObject =
    buildJsonObject {
        put("protocolVersion", params.text("protocolVersion") ?: DEFAULT_PROTOCOL)
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", "kotlin-editor-team")
            put("version", "1")
        }
    }

private fun listed(offered: McpTools): JsonObject =
    buildJsonObject { putJsonArray("tools") { add(delegateTool(offered.members())) } }

// Why a call cannot run, as the result the manager reads; null when it can.
private fun problemWith(member: String, task: String, members: List<String>): ToolOutcome? =
    when {
        member !in members -> unknownMember(member, members)
        task.isBlank() -> ToolOutcome("The task is empty.", isError = true)
        else -> null
    }

private fun unknownMember(member: String, members: List<String>): ToolOutcome =
    ToolOutcome("There is no member named \"$member\". The members are: ${members.joinToString(", ")}.", isError = true)

private fun delegateTool(members: List<String>): JsonObject =
    buildJsonObject {
        put("name", DELEGATE_TOOL)
        put(
            DESCRIPTION,
            "Delegates a task to a member of your team and returns the member's report. Use it to hand work over " +
                "instead of doing it yourself. The member has no access to this conversation: put everything it " +
                "needs in the task (the goal, the files, the constraints).",
        )
        putJsonObject("inputSchema") {
            put(TYPE, "object")
            putJsonObject("properties") {
                putJsonObject(MEMBER) {
                    put(TYPE, STRING)
                    put(DESCRIPTION, "The name of the member to delegate to.")
                    put("enum", JsonArray(members.map(::JsonPrimitive)))
                }
                putJsonObject(TASK) {
                    put(TYPE, STRING)
                    put(DESCRIPTION, "What the member must do, complete and self-contained.")
                }
            }
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive(MEMBER))
                    add(JsonPrimitive(TASK))
                },
            )
        }
    }

private fun toolResult(outcome: ToolOutcome): JsonObject =
    buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put(TYPE, "text")
                    put("text", outcome.text)
                },
            )
        }
        put("isError", outcome.isError)
    }

private fun success(id: JsonElement, result: JsonObject): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

private fun failure(id: JsonElement, code: Int, message: String): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
