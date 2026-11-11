package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The history of the conversations, which the agent keeps (the fake agent in a directory, as Claude Code in its own):
 * the server lists the sessions, replays them and reopens them after a restart, and stores nothing itself.
 */
class AgentHistoryTest {
    private val settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000)
    private val store = Files.createTempDirectory("agent-store")
    private val directory = Files.createTempDirectory("agent-history").toString()
    private val services = mutableListOf<AgentService>()
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        services.forEach(AgentService::close)
        store.toFile().deleteRecursively()
    }

    // A server: a restart is a new one, with the same agent and its sessions.
    private fun server(): AgentService = AgentService(settings = settings).also(services::add)

    private fun agent(vararg options: String) = AgentCommand(FakeAgentCommand.command("--store", "$store", *options))

    private fun AgentService.send(
        text: String,
        conversation: String = "c1",
        agent: AgentCommand = agent(),
        session: String? = null,
    ) = prompt(conversation, agent, directory, "u${++prompts}", text, session)

    // A first server: one conversation of two prompts; returns the id of its session.
    private fun pastSession(agent: AgentCommand = agent()): String {
        val first = server()
        first.send("echo hello there", agent = agent)
        first.idle("c1") { it.answers().isNotEmpty() }
        first.send("echo second prompt", agent = agent)
        val followed = first.idle("c1") { it.answers().size == 2 }
        first.close()
        return checkNotNull(followed.last.session) { "No session" }
    }

    @Test
    fun `the sessions of the agent are listed with their title, for the directory only`() {
        val session = pastSession()
        val other = server()
        other.send("echo elsewhere", conversation = "c2")
        other.idle("c2") { it.answers().isNotEmpty() }
        val elsewhere = Files.createTempDirectory("agent-elsewhere").toString()
        other.prompt("c3", agent(), elsewhere, "x", "echo far away")
        other.idle("c3") { it.answers().isNotEmpty() }
        val listed = runBlocking { other.processes.sessions(agent(), listOf(directory)) }
        assertTrue(listed.supported)
        assertEquals(2, listed.sessions.size)
        assertTrue(listed.sessions.all { it.directory == directory })
        assertEquals("echo hello there", listed.sessions.firstOrNull { it.id == session }?.title)
    }

    @Test
    fun `opening a session replays its history, in order`() {
        val session = pastSession()
        val server = server()
        server.open("c1", agent(), directory, session)
        val followed = server.idle("c1") { it.size >= 4 }
        val texts =
            followed.items.map { item ->
                when (item) {
                    is AgentItem.UserText -> "user: ${item.text}"
                    is AgentItem.AgentText -> "agent: ${item.text}"
                    else -> "other"
                }
            }
        val expected =
            listOf("user: echo hello there", "agent: hello there ", "user: echo second prompt", "agent: second prompt ")
        assertEquals(expected, texts)
        assertTrue(followed.last.replayed)
        assertTrue(followed.last.replayable)
        assertEquals(session, followed.last.session)
        assertEquals("echo hello there", followed.last.title)
    }

    @Test
    fun `a conversation goes on in its session after a restart, without a replay`() {
        val session = pastSession()
        val restarted = server()
        restarted.send("count", session = session)
        val followed = restarted.idle("c1") { it.answers().isNotEmpty() }
        assertEquals(listOf("Prompt 3 of $session."), followed.items.answers())
        assertFalse(followed.last.replayed)
        assertTrue(followed.items.none { it is AgentItem.Notice })
    }

    @Test
    fun `an agent that cannot replay goes on from where it was`() {
        val session = pastSession(agent("--no-load"))
        val server = server()
        server.open("c1", agent("--no-load"), directory, session)
        val opened = server.idle("c1") { it.isNotEmpty() }
        assertContains(opened.items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }, "does not replay")
        assertFalse(opened.last.replayed)
        assertFalse(opened.last.replayable)
        server.send("count", agent = agent("--no-load"))
        val followed = server.idle("c1") { it.answers().isNotEmpty() }
        assertEquals(listOf("Prompt 3 of $session."), followed.items.answers())
    }

    @Test
    fun `an agent without history starts a new session, and says so`() {
        val session = pastSession(agent("--no-history"))
        val server = server()
        val listed = runBlocking { server.processes.sessions(agent("--no-history"), listOf(directory)) }
        assertFalse(listed.supported)
        server.send("count", agent = agent("--no-history"), session = session)
        val followed = server.idle("c1") { it.answers().isNotEmpty() }
        assertContains(followed.items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }, "new session")
        assertContains(followed.items.answers().joinToString(), "Prompt 1 of")
    }

    @Test
    fun `a deleted session is no longer listed`() {
        val session = pastSession()
        val server = server()
        assertTrue(runBlocking { server.processes.delete(agent(), session) })
        val listed = runBlocking { server.processes.sessions(agent(), listOf(directory)) }
        assertNull(listed.sessions.firstOrNull { it.id == session })
    }

    @Test
    fun `an agent that stopped reopens the session in its new process`() {
        val server = server()
        server.send("echo before")
        server.idle("c1") { it.answers().isNotEmpty() }
        server.send("crash")
        server.idle("c1") { list -> list.any { it is AgentItem.Notice } }
        server.send("count")
        val followed = server.idle("c1") { list -> list.answers().any { it.startsWith("Prompt") } }
        // The turn that crashed is lost, the agent remembers the one before.
        assertContains(followed.items.answers().last(), "Prompt 2 of")
        assertTrue(followed.items.filterIsInstance<AgentItem.Notice>().none { "new session" in it.text })
    }

    @Test
    fun `opening a session twice replays it once`() {
        val session = pastSession()
        val server = server()
        server.open("c1", agent(), directory, session)
        server.open("c1", agent(), directory, session)
        val followed = server.idle("c1") { it.size >= 4 }
        assertEquals(2, followed.items.count { it is AgentItem.UserText })
    }
}
