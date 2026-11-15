package org.example.kotlineditor.acp.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MCP endpoint a manager session reaches its `delegate` tool through, called over HTTP like Claude Code does. */
class McpEndpointTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoint = McpEndpoint(scope)
    private val calls = CopyOnWriteArrayList<String>()
    private val started = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()

    private val stub =
        object : McpTools {
            override fun members() = listOf("coder", "tester")

            override suspend fun delegate(member: String, task: String, toolUseId: String?): ToolOutcome {
                calls.add("$member|$task|$toolUseId")
                if (task == "wait") {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                return ToolOutcome("report of $member")
            }
        }
    private val access = endpoint.register(stub)
    private val client = McpClient(access.url, access.token)

    @AfterTest
    fun tearDown() {
        endpoint.close()
        scope.cancel()
    }

    @Test
    fun `a request without the token of the session is refused`() {
        assertEquals(401, McpClient(access.url, null).post("{'jsonrpc':'2.0','id':1,'method':'ping'}").status)
        assertEquals(401, McpClient(access.url, "other").post("{'jsonrpc':'2.0','id':1,'method':'ping'}").status)
        assertEquals(200, client.post("{'jsonrpc':'2.0','id':1,'method':'ping'}").status)
    }

    @Test
    fun `a token whose session ended is refused`() {
        endpoint.unregister(access.token)
        assertEquals(401, client.post("{'jsonrpc':'2.0','id':1,'method':'ping'}").status)
    }

    @Test
    fun `initialize answers the version asked, and initialized needs no answer`() {
        val answer =
            client.post(
                "{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18'}}",
            )
        assertEquals("2025-06-18", answer.result()["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in answer.result()["capabilities"]!!.jsonObject)
        val initialized = client.post("{'jsonrpc':'2.0','method':'notifications/initialized'}")
        assertEquals(202, initialized.status)
    }

    @Test
    fun `tools list offers delegate for the members of the team`() {
        val tools = client.post("{'jsonrpc':'2.0','id':2,'method':'tools/list'}").result()["tools"]!!.jsonArray
        val tool = tools.single().jsonObject
        assertEquals("delegate", tool["name"]!!.jsonPrimitive.content)
        assertContains(tool["description"]!!.jsonPrimitive.content, "no access to this conversation")
        val properties = tool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
        val members: JsonArray = properties["member"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(listOf("coder", "tester"), members.map { it.jsonPrimitive.content })
        assertEquals("string", properties["task"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call runs the delegation and answers its report as text`() {
        val answer = client.delegate(3, "coder", "write it", toolUseId = "toolu_1")
        assertEquals("report of coder", answer.report())
        assertFalse(answer.failed())
        assertEquals(3, answer.json()["id"]!!.jsonPrimitive.int)
        assertEquals(listOf("coder|write it|toolu_1"), calls.toList())
    }

    @Test
    fun `an unknown member is a failed tool result that lists the members`() {
        val rejected = client.delegate(4, "ghost", "x")
        assertTrue(rejected.failed())
        assertContains(rejected.report(), "coder, tester")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an unknown method or tool is a JSON-RPC error`() {
        val method = client.post("{'jsonrpc':'2.0','id':5,'method':'resources/list'}").json()
        assertEquals(-32_601, method["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val tool = client.post("{'jsonrpc':'2.0','id':6,'method':'tools/call','params':{'name':'other'}}").json()
        assertEquals(-32_602, tool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val broken = client.post("not json").json()
        assertEquals(-32_700, broken["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `notifications cancelled stops the call it names`() {
        var answer: Answer? = null
        val caller = thread { answer = client.delegate(7, "coder", "wait") }
        runBlockingWait(started)
        client.post("{'jsonrpc':'2.0','method':'notifications/cancelled','params':{'requestId':7}}")
        runBlockingWait(cancelled)
        caller.join(TimeUnit.SECONDS.toMillis(10))
        assertTrue(answer!!.failed())
    }

    private fun runBlockingWait(done: CompletableDeferred<Unit>) =
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { done.await() } }
}
