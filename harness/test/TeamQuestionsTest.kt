package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.TeamAgent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A member allowed to ask the user questions: they show in its conversation, and its work goes on with the answers. */
class TeamQuestionsTest {
    private val fixture = TeamFixture()
    private val lead = fixture.agent("Lead")
    private val spec = fixture.agent("Spec", canAsk = true)
    private val quiet = fixture.agent("Quiet")

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private fun questions(items: List<AgentItem>) =
        items.filterIsInstance<AgentItem.Permission>().filter { it.question }

    // The questions of [member] once [count] of them wait for the user.
    private fun waiting(member: TeamAgent, count: Int): List<AgentItem.Permission> {
        val seen = fixture.until(fixture.memberKey(member)) { items -> questions(items).count { it.pending } == count }
        return questions(seen.items)
    }

    private fun answer(member: TeamAgent, question: AgentItem.Permission, text: String) =
        fixture.service.answer(fixture.memberKey(member), question.id, text)

    @Test
    fun `a member asks its questions, all at once, and goes on with the answers`() {
        val team = fixture.team(lead, spec)
        fixture.send(team, "delegate Spec: ask Which database?=SQLite,Postgres ~ Why?")
        val asked = waiting(spec, 2)
        assertEquals(listOf("Which database?", "Why?"), asked.map { it.title })
        assertEquals(listOf("SQLite", "Postgres"), asked.firstOrNull()?.options?.map { it.name })
        // The card of the task stays running while the user thinks.
        val running = fixture.until("K") { items -> items.delegations().any { it.status == DelegationStatus.Running } }
        assertEquals(1, running.items.delegations().size)
        answer(spec, asked.first(), "SQLite")
        answer(spec, asked.last(), "it is small")
        val seen = fixture.finished()
        val report = seen.items.delegations().firstOrNull()
        assertEquals(DelegationStatus.Done, report?.status)
        assertContains(report?.result.orEmpty(), "Answer: SQLite")
        assertContains(report?.result.orEmpty(), "Answer: it is small")
        assertFalse(report?.result.orEmpty().contains("```ask"))
        // What the member's conversation shows: the questions answered, then what the user said as its next message.
        val member = fixture.until(fixture.memberKey(spec)) { items -> questions(items).all { !it.pending } }
        assertEquals(listOf("SQLite", "it is small"), questions(member.items).map { it.chosen })
    }

    @Test
    fun `a member that may not ask goes on, and the block is not shown in its report`() {
        val team = fixture.team(lead, quiet)
        fixture.send(team, "delegate Quiet: ask Which database?=SQLite,Postgres")
        val seen = fixture.finished()
        val report = seen.items.delegations().firstOrNull()
        assertEquals(DelegationStatus.Done, report?.status)
        assertEquals("I have questions.", report?.result?.trim())
        val member = fixture.until(fixture.memberKey(quiet)) { items -> items.answers().isNotEmpty() }
        assertTrue(questions(member.items).isEmpty())
    }

    @Test
    fun `stopping the team cancels the questions waiting, and the task`() {
        val team = fixture.team(lead, spec)
        fixture.send(team, "delegate Spec: ask Which database?=SQLite,Postgres")
        waiting(spec, 1)
        fixture.harness.cancel("K")
        val seen = fixture.idle { items -> items.delegations().all { it.status != DelegationStatus.Running } }
        assertEquals(DelegationStatus.Cancelled, seen.items.delegations().firstOrNull()?.status)
        val member = fixture.until(fixture.memberKey(spec)) { items -> questions(items).all { it.cancelled } }
        assertTrue(questions(member.items).all { it.cancelled && it.chosen == null })
    }

    @Test
    fun `a member that asks again after the answers is asked again, up to a limit`() {
        val team = fixture.team(lead, spec)
        fixture.send(team, "delegate Spec: ask First?=a,b")
        val first = waiting(spec, 1)
        answer(spec, first.first(), "a")
        // The fake agent answers the answers with a message, which holds no block: the task ends.
        val seen = fixture.finished()
        assertEquals(DelegationStatus.Done, seen.items.delegations().firstOrNull()?.status)
        assertEquals(1, questions(fixture.until(fixture.memberKey(spec)) { true }.items).size)
    }
}
