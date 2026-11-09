package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The server side of agent mode, with the fake ACP agent in its own JVM. */
class AgentServiceTest {
    private val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000))
    private val agent = AgentCommand(FakeAgentCommand.command())
    private val directory = Files.createTempDirectory("agent-test")
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        service.close()
        directory.toFile().deleteRecursively()
    }

    private fun send(text: String, conversation: String = "c1", command: AgentCommand = agent) {
        service.prompt(conversation, command, directory.toString(), "u${++prompts}", text)
    }

    // Follows the transcript like the IDE until the turn is over and [until] holds, and returns every item.
    private fun turn(conversation: String = "c1", until: (List<AgentItem>) -> Boolean = { true }): List<AgentItem> =
        follow(conversation) { state, items -> state == AgentTurnState.Idle && until(items) }

    // Follows the transcript until [until] holds, whatever the state.
    private fun waitFor(until: (List<AgentItem>) -> Boolean): List<AgentItem> =
        follow("c1") { _, items -> until(items) }

    private fun follow(conversation: String, done: (AgentTurnState, List<AgentItem>) -> Boolean): List<AgentItem> =
        service.follow(conversation, done).items

    @Test
    fun `the chunks of an answer make one text item`() {
        send("echo hello agent world")
        val items = turn { list -> list.any { it is AgentItem.AgentText } }
        assertIs<AgentItem.UserText>(items.firstOrNull())
        val answer = items.filterIsInstance<AgentItem.AgentText>()
        assertEquals(1, answer.size)
        assertEquals("hello agent world ", answer.firstOrNull()?.text)
    }

    @Test
    fun `a prompt sent twice runs once`() {
        service.prompt("c1", agent, directory.toString(), "same", "echo once")
        service.prompt("c1", agent, directory.toString(), "same", "echo once")
        val items = turn { list -> list.any { it is AgentItem.AgentText } }
        assertEquals(1, items.count { it is AgentItem.UserText })
    }

    @Test
    fun `a prompt arriving while the agent answers is queued and answered once it is done`() {
        val file = directory.resolve("queued-writes.txt")
        send("write $file first by the agent")
        val asked = waitFor { list -> list.any { it is AgentItem.Permission } }
        // The agent answers (it waits for the permission): the second prompt joins its queue, and the server takes it.
        send("count")
        val permission = asked.filterIsInstance<AgentItem.Permission>().first()
        service.answer("c1", permission.id, "allow")
        val followed = turn { list -> list.answers().any { it.startsWith("Prompt 2 of") } }
        assertEquals(2, followed.count { it is AgentItem.UserText })
        val answers = followed.answers()
        assertContains(answers.firstOrNull().orEmpty(), "Wrote")
        assertContains(answers.last(), "Prompt 2 of")
        assertTrue(followed.filterIsInstance<AgentItem.Notice>().isEmpty(), followed.toString())
    }

    @Test
    fun `a conversation keeps a queue of prompts while the agent answers, and refuses beyond it`() {
        send("stubborn")
        waitFor { list -> list.any { it is AgentItem.AgentText } }
        repeat(4) { send("count") } // the queue is now exactly [QUEUED_PROMPTS]
        // One more arrives with nobody running it yet: refused, and the client sends it again once a turn is over.
        val refused = runCatching { send("count") }.exceptionOrNull()
        assertIs<IllegalStateException>(refused)
        assertContains(refused.message.orEmpty(), AGENT_BUSY_MESSAGE)
        service.cancel("c1") // the agent stops; the queued prompts run in turn, the oldest first
        val followed = turn { list -> list.count { it is AgentItem.UserText } == 5 }
        assertContains(followed.filterIsInstance<AgentItem.Notice>().joinToString { it.text }, "Stopped.")
        // The four queued prompts were answered, after the cancelled one, and in their order.
        val text = followed.answers().joinToString(" ")
        for (turnNumber in 2..5) {
            assertEquals(1, Regex("Prompt $turnNumber of").findAll(text).count(), text)
        }
        assertTrue("Prompt 6" !in text, "the refused prompt was never run: $text")
    }

    @Test
    fun `a client of another epoch gets every item, a known one only the new ones`() {
        send("echo first")
        val all = turn { list -> list.any { it is AgentItem.AgentText } }
        val again = runBlocking { service.transcript("c1", "other", 1_000) }
        assertEquals(all.map { it.id }, again.items.map { it.id })
        val none = runBlocking { service.transcript("c1", service.epoch, again.version) }
        assertTrue(none.items.isEmpty())
    }

    @Test
    fun `an unknown conversation says so`() {
        val transcript = runBlocking { service.transcript("nobody", "old-epoch", 0) }
        assertFalse(transcript.known)
    }

    @Test
    fun `the agent reads through the client`() {
        val file = directory.resolve("notes.txt")
        file.writeText("one\ntwo\nthree")
        send("read $file")
        val items = turn { list -> list.any { it is AgentItem.AgentText } }
        val call = items.filterIsInstance<AgentItem.ToolCall>().firstOrNull()
        assertEquals(AgentToolKind.Read, call?.kind)
        assertEquals(AgentToolStatus.Completed, call?.status)
        assertEquals(listOf(file.toString()), call?.locations)
        assertContains(items.filterIsInstance<AgentItem.AgentText>().joinToString { it.text }, "Read 3 lines.")
    }

    @Test
    fun `a write waits for the permission, then writes the file`() {
        val file = directory.resolve("out.txt")
        send("write $file written by the agent")
        val asked = waitFor { list -> list.any { it is AgentItem.Permission } }
        val permission = asked.filterIsInstance<AgentItem.Permission>().first()
        assertEquals(listOf("Allow", "Reject"), permission.options.map { it.name })
        service.answer("c1", permission.id, "allow")
        service.answer("c1", permission.id, "reject")
        val items = turn { list -> list.any { (it as? AgentItem.AgentText)?.text?.startsWith("Wrote") == true } }
        assertEquals("written by the agent", file.readText())
        val answered = items.filterIsInstance<AgentItem.Permission>().first()
        assertEquals("allow", answered.chosen)
        val call = items.filterIsInstance<AgentItem.ToolCall>().first()
        assertEquals(AgentToolStatus.Completed, call.status)
        assertEquals(file.toString(), call.diffs.firstOrNull()?.path)
    }

    @Test
    fun `cancelling stops the agent and its permission requests`() {
        send("slow")
        waitFor { list -> list.any { it is AgentItem.AgentText } }
        service.cancel("c1")
        val items = turn { list -> list.any { it is AgentItem.Notice } }
        val notice = items.filterIsInstance<AgentItem.Notice>().last()
        assertEquals("Stopped.", notice.text)
    }

    @Test
    fun `an agent that does not stop is abandoned after the grace delay`() {
        send("stubborn")
        waitFor { list -> list.any { it is AgentItem.AgentText } }
        service.cancel("c1")
        val items = turn { list -> list.any { it is AgentItem.Notice } }
        assertEquals("Stopped.", items.filterIsInstance<AgentItem.Notice>().last().text)
    }

    @Test
    fun `a crash is explained, and the next prompt starts a new session`() {
        send("crash")
        val crashed = turn { list -> list.any { it is AgentItem.Notice } }
        val notice = crashed.filterIsInstance<AgentItem.Notice>().last()
        assertTrue(notice.error)
        assertContains(notice.text, "exit code 3")
        assertContains(notice.text, "fake agent crashed")
        send("count")
        val items = turn { list -> list.any { (it as? AgentItem.AgentText)?.text?.startsWith("Prompt") == true } }
        assertContains(items.filterIsInstance<AgentItem.Notice>().joinToString { it.text }, "new session")
        assertContains(items.filterIsInstance<AgentItem.AgentText>().last().text, "Prompt 1")
    }

    @Test
    fun `an agent that needs a login says how`() {
        send("echo hi", command = AgentCommand(FakeAgentCommand.command("--login")))
        val items = turn { list -> list.any { it is AgentItem.Notice } }
        val notice = items.filterIsInstance<AgentItem.Notice>().last()
        assertContains(notice.text, "Log in to the agent")
        assertContains(notice.text, "Run fake-login in a terminal")
    }

    @Test
    fun `a missing program is explained`() {
        send("echo hi", command = AgentCommand(listOf("no-such-agent-program-42")))
        val items = turn { list -> list.any { it is AgentItem.Notice } }
        assertContains(items.filterIsInstance<AgentItem.Notice>().last().text, "Cannot start the agent")
    }

    @Test
    fun `conversations of the same agent share its process, each in its session`() {
        send("count", conversation = "a")
        send("count", conversation = "b")
        val first = turn("a") { list -> list.any { it is AgentItem.AgentText } }
        val second = turn("b") { list -> list.any { it is AgentItem.AgentText } }
        val sessions =
            (first + second).filterIsInstance<AgentItem.AgentText>().map { it.text.substringAfter(" of ") }.toSet()
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a prompt of another agent starts a new session of that one`() {
        send("count")
        val before = turn { list -> list.any { it is AgentItem.AgentText } }
        assertTrue(before.answers().firstOrNull()?.startsWith("Prompt 1 of") == true)
        // Another agent (its own process, so a JVM argument that differs) takes the conversation over.
        val newcomer = AgentCommand(FakeAgentCommand.command("--no-commands"))
        send("count", command = newcomer)
        val items = turn { answersOf(it).count() == 2 }
        val notices = items.filterIsInstance<AgentItem.Notice>().joinToString(" ") { it.text }
        assertContains(notices, "new session")
        assertContains(notices, "previous agent")
        // The conversation keeps what the previous agent was told; the newcomer answers from its own first prompt.
        assertEquals(2, items.count { it is AgentItem.UserText })
        assertTrue(items.answers().last().startsWith("Prompt 1 of"))
    }

    @Test
    fun `a prompt of the agent the conversation ran continues its session`() {
        send("count")
        turn { list -> list.any { it is AgentItem.AgentText } }
        send("count")
        val items = turn { answersOf(it).count() == 2 }
        assertTrue(items.filterIsInstance<AgentItem.Notice>().isEmpty(), items.toString())
        assertTrue(items.answers().last().startsWith("Prompt 2 of"))
    }

    // The texts the agent answered so far, in [items].
    private fun answersOf(items: List<AgentItem>) = items.filterIsInstance<AgentItem.AgentText>()

    @Test
    fun `a plan is one item, updated`() {
        send("plan")
        val items = turn { list -> list.any { it is AgentItem.AgentText } }
        val plans = items.filterIsInstance<AgentItem.Plan>()
        assertEquals(1, plans.size)
        assertEquals(listOf("completed", "completed"), plans.first().entries.map { it.status })
    }
}
