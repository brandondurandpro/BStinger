package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeDirective
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 30_000L
private const val PROMPTS_SENT = 40
private const val KEPT_AT_LEAST = 30

/**
 * What an outside module (the coordination of a team) needs of a conversation: a prompt shown differently or not shown,
 * the outcome of a turn, items added to the transcript, a conversation kept busy, and being told that it was stopped.
 */
class ConversationControlTest {
    private val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000))
    private val agent = AgentCommand(FakeAgentCommand.command())
    private val directory = Files.createTempDirectory("agent-control").toString()
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        service.close()
    }

    // Sends [text] and returns the id of the prompt.
    private fun send(
        text: String,
        conversation: String = "c1",
        shown: String? = text,
        lease: ConversationLease? = null,
        command: AgentCommand = agent,
    ): String {
        val id = "u${++prompts}"
        service.prompt(conversation, command, directory, id, text, shown = shown, lease = lease)
        return id
    }

    private fun outcome(id: String, conversation: String = "c1"): TurnOutcome =
        runBlocking { withTimeout(TIMEOUT_MS) { service.awaitTurn(conversation, id) } }

    private fun stateOf(conversation: String = "c1"): AgentTurnState =
        runBlocking { service.transcript(conversation, service.epoch, 0).state }

    @Test
    fun `the transcript shows what the user typed, the agent receives the preamble too`() {
        val sent = FakeDirective.wrap("You are the manager of a team.", "echo hello team")
        val id = send(sent, shown = "echo hello team")
        val done = outcome(id)
        assertEquals(TurnEnding.Completed, done.ending)
        assertEquals("hello team", done.text)
        val items = service.idle("c1").items
        assertEquals(listOf("echo hello team"), items.filterIsInstance<AgentItem.UserText>().map { it.text })
    }

    @Test
    fun `a prompt shown as null leaves no user item and is still answered`() {
        val id = send("echo relayed reports", shown = null)
        val done = outcome(id)
        assertEquals("relayed reports", done.text)
        val items = service.idle("c1").items
        assertTrue(items.none { it is AgentItem.UserText }, items.toString())
        assertEquals(1, items.count { it is AgentItem.AgentText })
    }

    @Test
    fun `a prompt sent twice runs once, shown or not`() {
        service.prompt("c1", agent, directory, "same", "echo once", shown = null)
        service.prompt("c1", agent, directory, "same", "echo once", shown = null)
        val done = outcome("same")
        assertEquals("once", done.text)
        val items = service.idle("c1").items
        assertEquals(1, items.count { it is AgentItem.AgentText })
    }

    @Test
    fun `the text of a hidden turn does not continue the answer before it`() {
        val first = send("echo first", shown = null)
        assertEquals("first", outcome(first).text)
        val second = send("echo second", shown = null)
        assertEquals("second", outcome(second).text)
        val items = service.idle("c1").items
        assertEquals(listOf("first ", "second "), items.filterIsInstance<AgentItem.AgentText>().map { it.text })
    }

    @Test
    fun `a turn that shows several messages answers them in order`() {
        val id = send("tools 1")
        val done = outcome(id)
        assertEquals(TurnEnding.Completed, done.ending)
        assertEquals("Searched 1 times.", done.text)
    }

    @Test
    fun `cancelling a running turn ends it cancelled`() {
        val id = send("slow")
        service.follow("c1") { _, items -> items.any { it is AgentItem.AgentText } }
        service.cancel("c1")
        val done = outcome(id)
        assertEquals(TurnEnding.Cancelled, done.ending)
        assertContains(done.text, "tick 0")
    }

    @Test
    fun `an agent that dies fails the turn with the reason the transcript gives`() {
        val id = send("crash")
        val done = outcome(id)
        assertEquals(TurnEnding.Failed, done.ending)
        assertContains(done.reason, "exit code 3")
        val notices = service.idle("c1").items.filterIsInstance<AgentItem.Notice>()
        assertEquals(done.reason, notices.lastOrNull { it.error }?.text)
    }

    @Test
    fun `an agent that needs a login or does not start fails the turn`() {
        val login = send("echo hi", conversation = "a", command = AgentCommand(FakeAgentCommand.command("--login")))
        assertContains(outcome(login, "a").reason, "Log in to the agent")
        val missing = AgentCommand(listOf("no-such-agent-program-42"))
        val lost = send("echo hi", conversation = "b", command = missing)
        val done = outcome(lost, "b")
        assertEquals(TurnEnding.Failed, done.ending)
        assertContains(done.reason, "Cannot start the agent")
    }

    @Test
    fun `a queued prompt is awaited until its own turn ends`() {
        val running = send("slow")
        service.follow("c1") { _, items -> items.any { it is AgentItem.AgentText } }
        val queued = send("echo second")
        service.cancel("c1")
        assertEquals(TurnEnding.Cancelled, outcome(running).ending)
        val done = outcome(queued)
        assertEquals(TurnEnding.Completed, done.ending)
        assertEquals("second", done.text)
    }

    @Test
    fun `an unknown prompt is refused and an unknown conversation ends cancelled`() {
        send("echo hi")
        assertFailsWith<IllegalArgumentException> { outcome("never-sent") }
        assertEquals(TurnEnding.Cancelled, outcome("u1", "nobody").ending)
    }

    @Test
    fun `a long conversation keeps the outcome of its recent prompts only`() {
        val ids = (1..PROMPTS_SENT).map { number -> send("echo answer$number", shown = null).also(::outcome) }
        val lastId = ids.last()
        assertEquals("answer$PROMPTS_SENT", outcome(lastId).text)
        val oldestKept = ids[PROMPTS_SENT - KEPT_AT_LEAST]
        assertEquals("answer${PROMPTS_SENT - KEPT_AT_LEAST + 1}", outcome(oldestKept).text)
        assertFailsWith<IllegalArgumentException> { outcome(ids.first()) }
        // Sent again, a forgotten prompt is still a prompt already run: it does not run twice.
        service.prompt("c1", agent, directory, ids.first(), "echo again", shown = null)
        val items = service.idle("c1").items
        assertEquals(PROMPTS_SENT, items.count { it is AgentItem.AgentText })
    }

    @Test
    fun `closing a conversation ends the turns waiting in it`() {
        val running = send("slow")
        service.follow("c1") { _, items -> items.any { it is AgentItem.AgentText } }
        val queued = send("echo never")
        runBlocking { service.close("c1") }
        assertEquals(TurnEnding.Cancelled, outcome(running).ending)
        assertEquals(TurnEnding.Cancelled, outcome(queued).ending)
    }

    @Test
    fun `an item added to the transcript reaches a client, and its updates too`() {
        send("echo hi")
        service.idle("c1") { it.answers().isNotEmpty() }
        val added = service.addItem("c1") { id -> delegation(id, DelegationStatus.Running) }
        assertIs<AgentItem.Delegation>(added)
        val seen = service.follow("c1") { _, items -> items.any { it.id == added.id } }
        val shown = seen.items.filterIsInstance<AgentItem.Delegation>().firstOrNull()
        assertEquals(DelegationStatus.Running, shown?.status)
        assertEquals(added.version, shown?.version)

        val changed =
            service.updateItem("c1", added.id) { item ->
                (item as AgentItem.Delegation).copy(status = DelegationStatus.Done, result = "all good")
            }
        assertTrue(changed)
        val later = runBlocking { service.transcript("c1", service.epoch, seen.last.version) }
        val update = later.items.filterIsInstance<AgentItem.Delegation>().firstOrNull()
        assertEquals(DelegationStatus.Done, update?.status)
        assertEquals("all good", update?.result)
        val updated = update?.version ?: 0L
        assertTrue(updated > added.version)
    }

    @Test
    fun `an item that does not exist is not updated`() {
        send("echo hi")
        assertFalse(service.updateItem("c1", "missing") { it })
        assertFalse(service.updateItem("nobody", "missing") { it })
    }

    @Test
    fun `a conversation kept busy never shows idle between two turns`() {
        val lease = service.keepBusy("c1")
        assertEquals(AgentTurnState.Running, stateOf())
        val first = send("echo one", shown = null, lease = lease)
        outcome(first)
        assertEquals(AgentTurnState.Running, stateOf())
        val second = send("echo two", shown = null, lease = lease)
        outcome(second)
        assertEquals(AgentTurnState.Running, stateOf())
        lease.release()
        service.idle("c1")
        assertEquals(AgentTurnState.Idle, stateOf())
        lease.release()
    }

    @Test
    fun `prompts of others wait for the release of the lease, those of the holder go first`() {
        val lease = service.keepBusy("c1")
        val user = send("echo from the user")
        val relay = send("echo from the holder", shown = null, lease = lease)
        assertEquals("from the holder", outcome(relay).text)
        assertEquals(AgentTurnState.Running, stateOf())
        assertEquals(
            1,
            service
                .follow("c1") { _, items -> items.answers().isNotEmpty() }
                .items
                .answers()
                .size,
        )
        lease.release()
        assertEquals("from the user", outcome(user).text)
        val items = service.idle("c1").items
        assertEquals(listOf("from the holder ", "from the user "), items.answers())
    }

    @Test
    fun `cancelling a conversation between two turns tells the holder`() {
        val lease = service.keepBusy("c1")
        assertFalse(lease.stopped.isCompleted)
        service.cancel("c1")
        assertEquals(StopCause.Cancelled, runBlocking { withTimeout(TIMEOUT_MS) { lease.stopped.await() } })
        assertEquals(AgentTurnState.Running, stateOf())
        lease.release()
        assertEquals(AgentTurnState.Idle, stateOf())
    }

    @Test
    fun `cancelling a turn running under a lease tells the holder and ends the turn`() {
        val lease = service.keepBusy("c1")
        val id = send("slow", shown = null, lease = lease)
        service.follow("c1") { _, items -> items.any { it is AgentItem.AgentText } }
        service.cancel("c1")
        assertEquals(StopCause.Cancelled, runBlocking { withTimeout(TIMEOUT_MS) { lease.stopped.await() } })
        assertEquals(TurnEnding.Cancelled, outcome(id).ending)
        lease.release()
    }

    @Test
    fun `closing a conversation tells the holder`() {
        val lease = service.keepBusy("c1")
        runBlocking { service.close("c1") }
        assertEquals(StopCause.Closed, runBlocking { withTimeout(TIMEOUT_MS) { lease.stopped.await() } })
        lease.release()
    }

    @Test
    fun `a lease of another conversation is refused`() {
        val lease = service.keepBusy("c1")
        assertFailsWith<IllegalArgumentException> { send("echo hi", conversation = "c2", lease = lease) }
        lease.release()
    }

    private fun delegation(id: String, status: DelegationStatus) =
        AgentItem.Delegation(id, 0, "Backend", "c1#backend", "write the api", status)
}
