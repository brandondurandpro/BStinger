package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files

private const val POLL_MS = 50L
private const val BIG_LIMIT = 10_000

/**
 * The `run` prompt of the [FakeAgent]: [FakeProgram] runs in a terminal of the client (`terminal/create`), shown in a
 * tool call, and the agent waits for it (`terminal/wait_for_exit`), or kills it when the prompt is cancelled.
 */
class FakeTerminalRun(
    private val session: String,
    private val request: (String, JsonObject) -> JsonElement,
    private val update: (String, String, JsonObjectBuilder.() -> Unit) -> Unit,
    private val cancelled: () -> Boolean,
) {
    /** Runs the program for `run` followed by [words]; returns the stop reason and what the agent answers. */
    fun run(words: List<String>): Pair<String, String> {
        val forever = words.firstOrNull() == "forever"
        val big = words.firstOrNull() == "big"
        val arguments =
            when {
                forever -> listOf("forever")
                big -> listOf("lines", words.getOrNull(1) ?: "1", "0")
                else -> listOf("lines", words.getOrNull(0) ?: "1", words.getOrNull(1) ?: "0")
            }
        val terminal = create(arguments, if (big) BIG_LIMIT else null)
        tool("tool_call", "in_progress", terminal)
        if (forever) return stopped(terminal)
        val code = request("terminal/wait_for_exit", ids(terminal)).jsonObject.number("exitCode")
        val output = request("terminal/output", ids(terminal)).jsonObject
        val lines = output.text("output").orEmpty().lines().count { it.startsWith("line ") }
        val truncated = output["truncated"]?.jsonPrimitive?.booleanOrNull == true
        tool("tool_call_update", if (code == 0) "completed" else "failed", terminal)
        request("terminal/release", ids(terminal))
        return END_TURN to "Exit code $code, $lines lines${if (truncated) ", truncated" else ""}."
    }

    // A command that never stops: killed once the prompt is cancelled.
    private fun stopped(terminal: String): Pair<String, String> {
        while (!cancelled()) Thread.sleep(POLL_MS)
        request("terminal/kill", ids(terminal))
        request("terminal/wait_for_exit", ids(terminal))
        tool("tool_call_update", "failed", terminal)
        request("terminal/release", ids(terminal))
        return "cancelled" to "Killed."
    }

    private fun create(arguments: List<String>, limit: Int?): String {
        val (java, args) = javaCommand(arguments)
        val params =
            buildJsonObject {
                put("sessionId", session)
                put("command", java)
                putJsonArray("args") { args.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("env") {
                    add(
                        buildJsonObject {
                            put("name", "FAKE_PROGRAM")
                            put("value", "1")
                        },
                    )
                }
                if (limit != null) put("outputByteLimit", limit)
            }
        return request("terminal/create", params).jsonObject.text("terminalId").orEmpty()
    }

    private fun tool(kind: String, status: String, terminal: String) =
        update(session, kind) {
            put("toolCallId", "run-$terminal")
            put("title", "Run the fake program")
            put("kind", "execute")
            put("status", status)
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "terminal")
                        put("terminalId", terminal)
                    },
                )
            }
        }

    private fun ids(terminal: String) =
        buildJsonObject {
            put("sessionId", session)
            put("terminalId", terminal)
        }

    private companion object {
        const val END_TURN = "end_turn"

        // `java @argfile FakeProgram <arguments>`: the classpath goes through an argument file, too long for Windows.
        fun javaCommand(arguments: List<String>): Pair<String, List<String>> {
            val java =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse("java")
            val classpath = System.getProperty("java.class.path").replace("""\""", """\\""")
            val argFile = Files.createTempFile("fake-program", ".args")
            argFile.toFile().deleteOnExit()
            Files.write(argFile, listOf("-Xmx64m", "-cp", "\"$classpath\""))
            return java to listOf("@$argFile", FakeProgram::class.java.name) + arguments
        }

        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

        fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    }
}
