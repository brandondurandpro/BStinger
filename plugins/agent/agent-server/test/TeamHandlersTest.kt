package org.example.kotlineditor.agent.server

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.rpc.RemoteAgents
import org.example.kotlineditor.rpc.RemoteException
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeManager
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 30_000L

/** The prompts of a team, its stop and its closing, through the RPC layer: a client, a real server, fake agents. */
class TeamHandlersTest {
    private val data = Files.createTempDirectory("team-handlers-data")
    private val project = Files.createTempDirectory("team-handlers-project")
    private val stores = Files.createTempDirectory("team-handlers-stores")
    private val settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000)
    private val server = EditorServer(LocalServices(data.toFile(), agentSettings = settings)).start()
    private val client = RpcClient("127.0.0.1", server.port, server.token)
    private val agents = RemoteAgents(client)

    private val lead = member("Lead")
    private val backend = member("Backend")
    private val tester = member("Tester")
    private val team = Team("team-1", "Crew", lead, listOf(backend, tester))
    private var sent = 0

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
        stores.toFile().deleteRecursively()
    }

    private fun member(name: String): TeamAgent {
        val store = Files.createDirectories(stores.resolve(name))
        val command = AgentCommand(FakeAgentCommand.command("--store", store.toString()))
        return TeamAgent("id-$name", name, "does $name things", command)
    }

    private fun send(text: String, conversation: String = "K", itemId: String = "u${++sent}") {
        runBlocking { agents.teamPrompt(conversation, team, project.toString(), itemId, text) }
    }

    // Follows [conversation] over the RPC layer, like the IDE, until [done] holds.
    private fun follow(conversation: String, done: (AgentTranscript, List<AgentItem>) -> Boolean): List<AgentItem> =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val items = LinkedHashMap<String, AgentItem>()
                var epoch = ""
                var since = 0L
                var finished = false
                while (!finished) {
                    val transcript = agents.transcript(conversation, epoch, since)
                    transcript.items.forEach { items[it.id] = it }
                    epoch = transcript.epoch
                    since = transcript.version
                    finished = done(transcript, items.values.toList())
                }
                items.values.toList()
            }
        }

    private fun finished(): List<AgentItem> =
        follow("K") { transcript, items ->
            val answers = items.filterIsInstance<AgentItem.AgentText>().map { it.text }
            val answered = answers.any { it.startsWith(FakeManager.FINAL_PREFIX) }
            transcript.state == AgentTurnState.Idle && answered
        }

    private fun running(key: String): List<AgentItem> =
        follow(key) { _, items -> items.filterIsInstance<AgentItem.AgentText>().isNotEmpty() }

    private fun waitingPermission(key: String): AgentItem.Permission {
        val items = follow(key) { _, all -> all.any { it is AgentItem.Permission && it.chosen == null } }
        return requireNotNull(items.filterIsInstance<AgentItem.Permission>().firstOrNull { it.chosen == null })
    }

    private fun known(key: String): Boolean = runBlocking { agents.transcript(key, "", 0).known }

    @Test
    fun `a team prompt runs the team, the members work in conversations of their own`() {
        send("delegate Backend: echo built | Tester: echo checked")
        val items = finished()
        val cards = items.filterIsInstance<AgentItem.Delegation>()
        assertEquals(listOf("Backend", "Tester"), cards.map { it.member })
        assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), cards.map { it.status })
        assertEquals(listOf("K#id-Backend", "K#id-Tester"), cards.map { it.conversation })
        val member = follow("K#id-Backend") { _, all -> all.any { it is AgentItem.AgentText } }
        assertEquals(listOf("echo built"), member.filterIsInstance<AgentItem.UserText>().map { it.text })
    }

    @Test
    fun `a team prompt sent twice runs once`() {
        send("delegate Backend: echo hi", itemId = "same")
        send("delegate Backend: echo hi", itemId = "same")
        val items = finished()
        assertEquals(1, items.filterIsInstance<AgentItem.UserText>().size)
        assertEquals(1, items.filterIsInstance<AgentItem.Delegation>().size)
    }

    @Test
    fun `stopping a team conversation stops its members`() {
        send("delegate Backend: slow | Tester: slow")
        running("K#id-Backend")
        running("K#id-Tester")
        runBlocking { agents.cancel("K") }
        val items =
            follow("K") { transcript, all ->
                val cards = all.filterIsInstance<AgentItem.Delegation>()
                val settled = cards.isNotEmpty() && cards.none { it.status == DelegationStatus.Running }
                transcript.state == AgentTurnState.Idle && settled
            }
        val cards = items.filterIsInstance<AgentItem.Delegation>()
        assertEquals(listOf(DelegationStatus.Cancelled, DelegationStatus.Cancelled), cards.map { it.status })
        for (key in listOf("K#id-Backend", "K#id-Tester")) {
            val member =
                follow(key) { transcript, all ->
                    val stopped = all.any { it is AgentItem.Notice }
                    transcript.state == AgentTurnState.Idle && stopped
                }
            assertEquals(listOf("Stopped."), member.filterIsInstance<AgentItem.Notice>().map { it.text })
        }
    }

    @Test
    fun `closing a team conversation closes its members' conversations`() {
        send("delegate Backend: slow | Tester: slow")
        running("K#id-Backend")
        running("K#id-Tester")
        runBlocking { agents.close("K") }
        assertFalse(known("K"))
        assertFalse(known("K#id-Backend"))
        assertFalse(known("K#id-Tester"))
    }

    @Test
    fun `the permission mode of a team conversation reaches its members`() {
        runBlocking { agents.setOption("K", lead.agent, "mode", "plan") }
        send("delegate Backend: mode")
        val items = finished()
        assertEquals("Mode: plan.", items.filterIsInstance<AgentItem.Delegation>().lastOrNull()?.result)
    }

    @Test
    fun `a prompt during a run waits, and one too many is refused with the message the IDE retries on`() {
        val file = project.resolve("a.txt")
        send("delegate Backend: write $file hi")
        val request = waitingPermission("K#id-Backend")
        repeat(4) { number -> send("echo queued$number") }
        val refused = assertFailsWith<RemoteException> { send("echo one too many") }
        assertContains(refused.message.orEmpty(), AGENT_BUSY_MESSAGE)
        runBlocking { agents.answer("K#id-Backend", request.id, "allow") }
        val items =
            follow("K") { transcript, all ->
                val answers = all.filterIsInstance<AgentItem.AgentText>().map { it.text }
                transcript.state == AgentTurnState.Idle && answers.any { it.startsWith("queued3") }
            }
        val typed = items.filterIsInstance<AgentItem.UserText>().map { it.text }
        assertEquals(5, typed.size)
        assertTrue(typed.lastOrNull() == "echo queued3", typed.toString())
    }
}
