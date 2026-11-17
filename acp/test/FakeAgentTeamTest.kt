package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.testkit.acp.FakeAgentCommand
import org.example.kotlineditor.testkit.acp.FakeDirective
import org.example.kotlineditor.testkit.acp.FakeManager
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 30_000L

private const val PREAMBLE = """You are Backend, a member of a team.
Others work here at the same time. End with a report."""

/** The fake agent as a member and as a manager of a team: the directive of a wrapped prompt, the scripted manager. */
class FakeAgentTeamTest {
    private val service = AgentService(settings = AgentSettings(holdMs = 2_000, cancelGraceMs = 1_000))
    private val agent = AgentCommand(FakeAgentCommand.command())
    private val directory = Files.createTempDirectory("fake-team")
    private var prompts = 0

    @AfterTest
    fun tearDown() {
        service.close()
        directory.toFile().deleteRecursively()
    }

    private fun ask(text: String, conversation: String = "m1", wrapped: Boolean = true): TurnOutcome {
        val sent = if (wrapped) FakeDirective.wrap(PREAMBLE, text) else text
        val id = "u${++prompts}"
        service.prompt(conversation, agent, directory.toString(), id, sent, shown = text)
        return runBlocking { withTimeout(TIMEOUT_MS) { service.awaitTurn(conversation, id) } }
    }

    @Test
    fun `the directive is the text after the last marker line, or the whole prompt`() {
        assertEquals("echo hi", FakeDirective.of("echo hi"))
        assertEquals("echo hi", FakeDirective.of(FakeDirective.wrap("a\nb", "echo hi")))
        val twice = listOf("x", "# User request", "first", "  # User request  ", "echo hi", "second line", "")
        val wrapped = twice.joinToString("\n")
        assertEquals("echo hi\nsecond line", FakeDirective.of(wrapped))
        assertEquals("say # User request", FakeDirective.of("say # User request"))
        assertEquals("", FakeDirective.of("preamble\n# User request"))
    }

    @Test
    fun `a member told echo in a wrapped prompt behaves as when told directly`() {
        assertEquals("hello", ask("echo hello", wrapped = false).text)
        assertEquals("hello", ask("echo hello", conversation = "m2").text)
    }

    @Test
    fun `a member told to write a file in a wrapped prompt asks, then writes it`() {
        val file = directory.resolve("out.txt")
        val id = "u${++prompts}"
        val sent = FakeDirective.wrap(PREAMBLE, "write $file hi")
        service.prompt("m1", agent, directory.toString(), id, sent, shown = "write")
        val asked = service.follow("m1") { _, items -> items.any { it is AgentItem.Permission } }
        val permission = asked.items.filterIsInstance<AgentItem.Permission>().first()
        service.answer("m1", permission.id, "allow")
        val done = runBlocking { withTimeout(TIMEOUT_MS) { service.awaitTurn("m1", id) } }
        assertEquals(TurnEnding.Completed, done.ending)
        assertEquals("hi", file.readText())
    }

    @Test
    fun `a member told to crash in a wrapped prompt fails its turn`() {
        val done = ask("crash")
        assertEquals(TurnEnding.Failed, done.ending)
        assertContains(done.reason, "fake agent crashed")
    }

    @Test
    fun `a slow member told so in a wrapped prompt runs until it is cancelled`() {
        val id = "u${++prompts}"
        val sent = FakeDirective.wrap(PREAMBLE, "slow")
        service.prompt("m1", agent, directory.toString(), id, sent, shown = "slow")
        service.follow("m1") { _, items -> items.any { it is AgentItem.AgentText } }
        service.cancel("m1")
        val done = runBlocking { withTimeout(TIMEOUT_MS) { service.awaitTurn("m1", id) } }
        assertEquals(TurnEnding.Cancelled, done.ending)
    }

    @Test
    fun `a manager delegates in a team block, then gives its final answer to the reports`() {
        val delegating = ask("delegate Backend: write a.txt hello | Tester: echo check")
        assertEquals(TurnEnding.Completed, delegating.ending)
        assertContains(delegating.text, "```team\n")
        val block = delegating.text.substringAfter("```team\n").substringBefore("\n```")
        val backend = """{"member":"Backend","task":"write a.txt hello"}"""
        val tester = """{"member":"Tester","task":"echo check"}"""
        val expected = """{"delegate":[$backend,$tester]}"""
        assertEquals(expected, block)
        val reports = listOf("## Backend", "Wrote a.txt.", "## Tester", "All checks passed.")
        val done = ask(reports.joinToString("\n"), wrapped = false)
        assertContains(done.text, FakeManager.FINAL_PREFIX)
        assertContains(done.text, "## Backend\nWrote a.txt.")
        assertContains(done.text, "All checks passed.")
        assertFalse("```team" in done.text)
    }

    @Test
    fun `a manager can delegate a second round before it answers`() {
        val first = ask("delegate A: one ;; B: two | A: three")
        assertContains(first.text, """{"delegate":[{"member":"A","task":"one"}]}""")
        val second = ask("A reported one")
        assertContains(second.text, """{"delegate":[{"member":"B","task":"two"},{"member":"A","task":"three"}]}""")
        val final = ask("B reported two, A reported three")
        assertTrue(final.text.startsWith(FakeManager.FINAL_PREFIX), final.text)
        assertContains(final.text, "B reported two, A reported three")
    }

    @Test
    fun `a manager names a member the team does not have, or sends a block that is not JSON`() {
        val unknown = ask("delegate Nobody: echo hi")
        assertContains(unknown.text, """{"member":"Nobody","task":"echo hi"}""")
        val malformed = ask("delegate-malformed", conversation = "m2")
        val broken = listOf("```team", """{"delegate": [oops""", "```").joinToString("\n")
        assertContains(malformed.text, broken)
        val answered = ask("Your team block is not valid JSON.", conversation = "m2")
        assertContains(answered.text, "Your team block is not valid JSON.")
    }

    @Test
    fun `after its final answer a manager session understands ordinary directives again`() {
        ask("delegate A: one")
        ask("reports")
        assertEquals("plain", ask("echo plain").text)
    }

    @Test
    fun `a directive that is not a manager one is not mistaken for one`() {
        assertNull(FakeManager.parse("echo delegate"))
        assertNull(FakeManager.parse("delegated stuff"))
    }
}
