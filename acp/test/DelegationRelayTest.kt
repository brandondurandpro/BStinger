package org.example.kotlineditor.acp.relay

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.kotlineditor.acp.AgentService
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.acp.answers
import org.example.kotlineditor.acp.follow
import org.example.kotlineditor.acp.idle
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.RelayMember
import org.example.kotlineditor.model.RelayTeam
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A team served by the IDE's server: a manager with a `delegate` tool, each delegation run as a conversation. */
class DelegationRelayTest {
    private val store = Files.createTempDirectory("relay-store")
    private val directory = Files.createTempDirectory("relay-test").toString()
    private val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000))
    private val agent = AgentCommand(FakeAgentCommand.command("--store", "$store"))
    private var prompts = 0

    private val team =
        RelayTeam(
            RelayMember("lead"),
            listOf(
                RelayMember("coder", prompt = "echo Brief:", model = "smart", effort = "HIGH"),
                RelayMember("plain"),
            ),
        )

    @AfterTest
    fun tearDown() {
        service.close()
        store.toFile().deleteRecursively()
    }

    private fun startManager(text: String = "count"): McpClient {
        service.prompt("m", agent, directory, "u${++prompts}", text, relay = team)
        service.idle("m") { it.isNotEmpty() }
        val access = assertNotNull(service.delegations.access("m"))
        return McpClient(access.url, access.token)
    }

    @Test
    fun `the manager session starts with the delegate tool and without the launchers of the agent`() {
        service.prompt("m", agent, directory, "u1", "setup", relay = team)
        val setup =
            service
                .idle("m") { it.answers().isNotEmpty() }
                .items
                .answers()
                .last()
        val json = Json.parseToJsonElement(setup).jsonObject
        val server = json["mcpServers"]!!.jsonArray.single().jsonObject
        assertEquals("http", server["type"]!!.jsonPrimitive.content)
        assertEquals("kotlin-editor-team", server["name"]!!.jsonPrimitive.content)
        val header = server["headers"]!!.jsonArray.single().jsonObject
        assertEquals("Authorization", header["name"]!!.jsonPrimitive.content)
        assertEquals("Bearer ${service.delegations.access("m")!!.token}", header["value"]!!.jsonPrimitive.content)
        val claude = json["_meta"]!!.jsonObject["claudeCode"]!!.jsonObject
        val disallowed = claude["options"]!!.jsonObject["disallowedTools"]!!.jsonArray
        assertEquals(listOf("Task", "Agent"), disallowed.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a conversation without a team gets neither the tool nor the meta`() {
        service.prompt("plain", agent, directory, "u1", "setup")
        val setup =
            service
                .idle("plain") { it.answers().isNotEmpty() }
                .items
                .answers()
                .last()
        assertEquals("""{"mcpServers":[],"_meta":null}""", setup)
        assertNull(service.delegations.access("plain"))
    }

    @Test
    fun `a delegation runs the member in its own conversation and returns its last answer`() {
        val client = startManager()
        val answer = client.delegate(1, "plain", "echo all done")
        assertFalse(answer.failed(), answer.body)
        assertEquals("all done", answer.report().trim())
        val child = service.idle("m~d1") { it.answers().isNotEmpty() }
        assertEquals(listOf("all done "), child.items.answers())
        assertEquals(AgentTurnState.Idle, child.last.state)
    }

    @Test
    fun `the member works with its own model and effort, and gets its brief before the task`() {
        val client = startManager()
        val answer = client.delegate(1, "coder", "do it")
        assertEquals(
            "Brief: # Task do it",
            answer
                .report()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" "),
        )
        val options = service.idle("m~d1").last.options
        assertEquals("fake-smart", options.first { it.category == AgentOption.MODEL }.current)
        assertEquals("high", options.first { it.category == AgentOption.THOUGHT_LEVEL }.current)
    }

    @Test
    fun `a delegation with a model starts one session, not a probe session more`() {
        val client = startManager()
        client.delegate(1, "coder", "do it")
        service.prompt("m", agent, directory, "u9", "sessions", relay = team)
        val said =
            service
                .idle(
                    "m",
                ) { it.answers().any { text -> text.startsWith("Sessions created") } }
                .items
                .answers()
        assertEquals("Sessions created: 2.", said.last())
    }

    @Test
    fun `the manager runs with a long tool timeout, the members do not need it`() {
        val timeout = "MCP_TOOL_TIMEOUT"
        val command = agent.forManager()
        assertEquals("1800000", command.environment[timeout])
        assertEquals(null, agent.environment[timeout])
    }

    @Test
    fun `the member does not get the delegate tool`() {
        val client = startManager()
        val answer = client.delegate(1, "plain", "setup")
        assertEquals("""{"mcpServers":[],"_meta":null}""", answer.report())
    }

    @Test
    fun `delegations are numbered one after the other`() {
        val client = startManager()
        client.delegate(1, "plain", "echo first")
        client.delegate(2, "plain", "echo second")
        assertEquals(listOf("second "), service.idle("m~d2") { it.answers().isNotEmpty() }.items.answers())
        assertEquals(listOf("first "), service.idle("m~d1") { it.answers().isNotEmpty() }.items.answers())
    }

    @Test
    fun `the session of a delegation is not listed in the history`() {
        val client = startManager()
        client.delegate(1, "plain", "echo hi")
        val sessions = runBlocking { service.processes.sessions(agent, listOf(directory)) }
        val managerSession = service.idle("m").last.session
        assertEquals(listOf(managerSession), sessions.sessions.map { it.id })
    }

    @Test
    fun `cancelling the manager stops the delegation running and its answer comes back`() {
        val client = startManager()
        var answer: Answer? = null
        val caller = thread { answer = client.delegate(1, "plain", "slow") }
        service.follow("m~d1") { state, items -> state == AgentTurnState.Running && items.answers().isNotEmpty() }
        service.cancel("m")
        caller.join(15_000)
        assertNotNull(answer)
        service.idle("m~d1")
    }

    @Test
    fun `closing the manager closes its delegations and stops serving its token`() {
        val client = startManager()
        client.delegate(1, "plain", "echo hi")
        assertNotNull(service.conversations["m~d1"])
        runBlocking { service.close("m") }
        assertNull(service.conversations["m~d1"])
        assertEquals(401, client.post("{'jsonrpc':'2.0','id':1,'method':'ping'}").status)
        assertNull(service.delegations.access("m"))
    }
}
