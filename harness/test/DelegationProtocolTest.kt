package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DelegationProtocolTest {
    private val protocol = TextDelegationProtocol()

    private val team = Team("t", "Crew", agent("Lead"), listOf(agent("Backend"), agent("Tester")))

    private fun agent(name: String) = TeamAgent("id-$name", name, "does $name", AgentCommand(listOf("agent")))

    private fun lines(vararg parts: String) = parts.joinToString("\n")

    private fun block(json: String) = lines("```team", json, "```")

    private fun delegation(answer: String): ManagerReply.Delegate {
        val reply = protocol.parse(answer, team)
        return assertIs<ManagerReply.Delegate>(reply, reply.toString())
    }

    private fun invalid(answer: String): ManagerReply.Invalid {
        val reply = protocol.parse(answer, team)
        return assertIs<ManagerReply.Invalid>(reply, reply.toString())
    }

    @Test
    fun `a block delegates its tasks in the order written`() {
        val backend = """{"member": "Backend", "task": "write the api"}"""
        val tester = """{"member": "Tester", "task": "test it"}"""
        val json = """{"delegate": [$backend, $tester]}"""
        val reply = delegation(block(json))
        assertEquals(listOf("Backend", "Tester"), reply.tasks.map { it.member.name })
        assertEquals(listOf("write the api", "test it"), reply.tasks.map { it.task })
        assertEquals(
            "id-Backend",
            reply.tasks
                .firstOrNull()
                ?.member
                ?.id,
        )
        assertEquals("", reply.message)
    }

    @Test
    fun `text around the block is the message and the block is not part of it`() {
        val block = block("""{"delegate": [{"member": "Backend", "task": "go"}]}""")
        val answer = lines("I will split the work.", "", block, "", "Back soon.")
        val reply = delegation(answer)
        assertEquals(lines("I will split the work.", "", "", "Back soon."), reply.message)
    }

    @Test
    fun `an answer without a block asks for nothing`() {
        val answer = lines("All done, here is the summary.", "```kotlin", "val x = 1", "```")
        assertEquals(ManagerReply.Answer(answer), protocol.parse(answer, team))
    }

    @Test
    fun `with several blocks the last one wins and the earlier ones stay in the text`() {
        val first = block("""{"delegate": [{"member": "Backend", "task": "first"}]}""")
        val last = block("""{"delegate": [{"member": "Tester", "task": "last"}]}""")
        val reply = delegation(lines(first, "On second thought:", last))
        assertEquals(listOf("last"), reply.tasks.map { it.task })
        assertContains(reply.message, "first")
        assertContains(reply.message, "On second thought:")
    }

    @Test
    fun `a last block that is broken is not replaced by an earlier valid one`() {
        val valid = block("""{"delegate": [{"member": "Backend", "task": "first"}]}""")
        val problem = invalid(lines(valid, block("{oops"))).problem
        assertContains(problem, "not valid JSON")
    }

    @Test
    fun `a block that is not JSON, not closed, or not a delegation is invalid with a reason`() {
        assertContains(invalid(block("{\"delegate\": [oops")).problem, "not valid JSON")
        assertContains(invalid(lines("Working.", "```team", """{"delegate": []}""")).problem, "not closed")
        assertContains(invalid(block("""{"tasks": []}""")).problem, "{\"delegate\": [...]}")
        assertContains(invalid(block("""{"delegate": []}""")).problem, "empty")
        assertContains(invalid(block("""[1, 2]""")).problem, "{\"delegate\": [...]}")
    }

    @Test
    fun `an entry without a member or without a task is invalid`() {
        assertContains(invalid(block("""{"delegate": [{"member": "Backend"}]}""")).problem, "`member` and a `task`")
        assertContains(invalid(block("""{"delegate": [{"task": "x"}]}""")).problem, "`member` and a `task`")
        assertContains(invalid(block("""{"delegate": [{"member": "Backend", "task": "  "}]}""")).problem, "`task`")
        assertContains(invalid(block("""{"delegate": ["Backend"]}""")).problem, "`member`")
    }

    @Test
    fun `an unknown member is invalid and the reply lists the members`() {
        val reply = invalid(block("""{"delegate": [{"member": "Designer", "task": "x"}]}"""))
        assertContains(reply.problem, "\"Designer\"")
        assertContains(reply.problem, "Backend, Tester")
    }

    @Test
    fun `one unknown member among valid ones invalidates the whole block`() {
        val json = """{"delegate": [{"member": "Backend", "task": "x"}, {"member": "Nobody", "task": "y"}]}"""
        assertContains(invalid(block(json)).problem, "Nobody")
    }

    @Test
    fun `a member name is found whatever its case`() {
        val reply = delegation(block("""{"delegate": [{"member": " backend ", "task": "x"}]}"""))
        assertEquals(
            "Backend",
            reply.tasks
                .firstOrNull()
                ?.member
                ?.name,
        )
    }

    @Test
    fun `the instructions name the block, the members and the rules`() {
        val text = protocol.instructions(team)
        assertContains(text, "```team")
        assertContains(text, "Backend, Tester")
        assertTrue("last `team` block" in text)
    }
}
