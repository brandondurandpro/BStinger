package org.example.kotlineditor.acp.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** What an HTTP answer of the endpoint says. */
class Answer(val status: Int, val body: String) {
    /** The JSON-RPC message of the answer. */
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** Its `result`. */
    fun result(): JsonObject = json()["result"]!!.jsonObject

    /** The text of the first content of its result: the report of a tool call. */
    fun report(): String {
        val content = result()["content"]!!.jsonArray
        val first = content.first().jsonObject
        return first["text"]!!.jsonPrimitive.content
    }

    /** Whether the tool call it answers failed. */
    fun failed(): Boolean = result()["isError"]!!.jsonPrimitive.boolean
}

/** A client of [McpEndpoint] in the tests: one POST, one JSON answer. */
class McpClient(private val url: String, private val token: String?) {
    private val http = HttpClient.newHttpClient()

    /** Posts [json] (single quotes stand for double ones). */
    fun post(json: String): Answer {
        val body = HttpRequest.BodyPublishers.ofString(json.replace('\'', '"'))
        val builder = HttpRequest.newBuilder(URI.create(url)).POST(body)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val answer = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(answer.statusCode(), answer.body())
    }

    /** Posts a `tools/call` of `delegate`. */
    fun delegate(id: Int, member: String, task: String, toolUseId: String? = null): Answer {
        val meta = if (toolUseId == null) "" else ",'_meta':{'claudecode/toolUseId':'$toolUseId'}"
        val arguments = "{'member':'$member','task':'$task'}"
        return post(
            "{'jsonrpc':'2.0','id':$id,'method':'tools/call','params':{'name':'delegate','arguments':$arguments$meta}}",
        )
    }
}
