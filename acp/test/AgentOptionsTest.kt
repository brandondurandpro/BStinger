package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings of the sessions (the model…): offered by the agent, chosen by the user before or after it started. */
class AgentOptionsTest {
    private val settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000)
    private val store = Files.createTempDirectory("agent-store")
    private val directory = Files.createTempDirectory("agent-options").toString()
    private val services = mutableListOf<AgentService>()
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        services.forEach(AgentService::close)
        store.toFile().deleteRecursively()
    }

    private fun server(): AgentService = AgentService(settings = settings).also(services::add)

    private fun agent(vararg options: String) = AgentCommand(FakeAgentCommand.command("--store", "$store", *options))

    private fun AgentService.send(text: String, agent: AgentCommand = agent()) =
        prompt("c1", agent, directory, "u${++prompts}", text)

    private fun List<AgentOption>.model(): String? = firstOrNull { it.category == AgentOption.MODEL }?.current

    // The model of [conversation] once the agent is idle and [until] holds.
    private fun AgentService.modelOf(conversation: String, until: (List<AgentItem>) -> Boolean = { true }): String? {
        val followed = idle(conversation, until)
        return followed.last.options.model()
    }

    @Test
    fun `the models are offered before any session, and the probe leaves no session in the history`() {
        val server = server()
        val agent = agent()
        val options = runBlocking { server.processes.options(agent, directory) }
        val model = options.firstOrNull { it.id == "model" }
        assertEquals("fake-fast", model?.current)
        assertEquals(listOf("fake-fast", "fake-smart", "fake-max"), model?.values?.map { it.value })
        assertEquals(listOf("Fast", "Smart", "Max"), model?.values?.map { it.name })
        assertEquals("mode", options.firstOrNull { it.id == "mode" }?.category)
        val history = runBlocking { server.processes.sessions(agent, listOf(directory)) }
        assertTrue(history.sessions.isEmpty(), "Probe kept: ${history.sessions}")
    }

    @Test
    fun `a model chosen before the first prompt is the model of the session`() {
        val server = server()
        val agent = agent()
        runBlocking { server.processes.options(agent, directory) }
        runBlocking { server.setOption("c1", agent, "model", "fake-smart") }
        assertEquals("fake-smart", server.modelOf("c1"))
        server.send("model", agent)
        val followed = server.idle("c1") { it.answers().isNotEmpty() }
        assertEquals(listOf("Model: fake-smart."), followed.items.answers())
        assertEquals("fake-smart", followed.last.options.model())
    }

    @Test
    fun `the model changes between two prompts of a session`() {
        val server = server()
        server.send("echo hi")
        assertEquals("fake-fast", server.modelOf("c1") { it.answers().isNotEmpty() })
        runBlocking { server.setOption("c1", agent(), "model", "fake-max") }
        server.send("model")
        val followed = server.idle("c1") { it.answers().size == 2 }
        assertEquals("Model: fake-max.", followed.items.answers().lastOrNull())
        assertEquals("fake-max", followed.last.options.model())
    }

    @Test
    fun `a model the agent switches to itself shows`() {
        val server = server()
        server.send("echo hi")
        server.idle("c1") { it.answers().isNotEmpty() }
        runBlocking { server.setOption("c1", agent(), "model", "fake-smart") }
        server.send("fallback")
        val followed = server.idle("c1") { it.answers().size == 2 }
        assertEquals("fake-fast", followed.last.options.model())
    }

    @Test
    fun `a model the agent refuses fails, and the session keeps its model`() {
        val server = server()
        server.send("echo hi")
        server.idle("c1") { it.answers().isNotEmpty() }
        val failure = runCatching { runBlocking { server.setOption("c1", agent(), "model", "gpt-none") } }
        assertTrue(failure.isFailure)
        assertEquals("fake-fast", server.modelOf("c1"))
    }

    @Test
    fun `a refused choice made before the session started becomes a notice`() {
        val server = server()
        val agent = agent()
        runBlocking { server.setOption("c1", agent, "model", "gpt-none") }
        server.send("model", agent)
        val items = server.idle("c1") { it.answers().isNotEmpty() }.items
        val notices = items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }
        assertContains(notices, "gpt-none")
        assertEquals(listOf("Model: fake-fast."), items.answers())
    }

    @Test
    fun `an agent without settings offers none`() {
        val server = server()
        val agent = agent("--no-config")
        assertTrue(runBlocking { server.processes.options(agent, directory) }.isEmpty())
        server.send("echo hi", agent)
        val followed = server.idle("c1") { it.answers().isNotEmpty() }
        assertTrue(followed.last.options.isEmpty())
    }

    @Test
    fun `the model of a session reopened after a restart is the one the agent kept`() {
        val first = server()
        first.send("echo hi")
        first.idle("c1") { it.answers().isNotEmpty() }
        runBlocking { first.setOption("c1", agent(), "model", "fake-max") }
        val session = first.idle("c1").last.session
        first.close()
        val second = server()
        second.prompt("c1", agent(), directory, "u${++prompts}", "model", session)
        val followed = second.idle("c1") { it.answers().isNotEmpty() }
        assertEquals(listOf("Model: fake-max."), followed.items.answers())
        assertEquals("fake-max", followed.last.options.model())
    }

    @Test
    fun `config options are read flat or grouped, and the other types are left out`() {
        val message =
            Json.parseToJsonElement(
                """
                {"configOptions":[
                  {"id":"model","name":"Model","category":"model","type":"select","currentValue":"b",
                   "options":[{"group":"g","name":"G","options":[{"value":"a","name":"A"}]},{"value":"b"}]},
                  {"id":"fast","name":"Fast","type":"boolean","currentValue":true},
                  {"id":"effort","name":"Effort","type":"select","currentValue":"low",
                   "options":[{"value":"low","name":"Low","description":"Quick"}]}
                ]}
                """.trimIndent(),
            ) as JsonObject
        val options = message.configOptions().orEmpty()
        assertEquals(listOf("model", "effort"), options.map { it.id })
        val model = options.firstOrNull()?.values.orEmpty()
        assertEquals(listOf("a", "b"), model.map { it.value })
        assertEquals("b", model.lastOrNull()?.name)
        val effort = options.lastOrNull()
        assertNull(effort?.category)
        assertEquals("Quick", effort?.values?.firstOrNull()?.description)
        assertNull(JsonObject(emptyMap()).configOptions())
    }
}
