package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.TeamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of what a team conversation shows: the plumbing hidden, the status of a member, the work of a task. */
class AgentTeamRunTest {
    private fun lines(vararg parts: String): String = parts.joinToString("\n")

    private fun user(id: String, text: String) = AgentItem.UserText(id, 1, text)

    private fun answer(id: String, text: String) = AgentItem.AgentText(id, 1, text)

    private fun task(id: String, member: String, text: String, status: DelegationStatus) =
        AgentItem.Delegation(id, 1, member, "K#$member", text, status)

    private val block = lines(TeamText.BLOCK_OPENING, """{"delegate": [{"member": "Backend", "task": "build"}]}""", TeamText.BLOCK_CLOSING)

    @Test
    fun `an answer of the manager shows without its team block`() {
        val shown = shownInTeam(answer("a", lines("I will delegate.", "", block)), replayed = false, sentHere = false)
        assertEquals(answer("a", "I will delegate."), shown)
    }

    @Test
    fun `an answer that is only a block shows nothing, also while it streams`() {
        assertNull(shownInTeam(answer("a", block), replayed = false, sentHere = false))
        assertNull(shownInTeam(answer("a", lines(TeamText.BLOCK_OPENING, "{\"dele")), replayed = false, sentHere = false))
    }

    @Test
    fun `an answer without a block is shown as it is`() {
        val plain = answer("a", "All done.\n")
        assertEquals(plain, shownInTeam(plain, replayed = true, sentHere = false))
    }

    @Test
    fun `a live prompt is shown as typed, even with the heading of the harness in it`() {
        val typed = user("prompt-1", lines("look", TeamText.REQUEST_HEADING, "this"))
        assertEquals(typed, shownInTeam(typed, replayed = false, sentHere = true))
        // Sent by this window into a conversation whose history was replayed: still as typed.
        assertEquals(typed, shownInTeam(typed, replayed = true, sentHere = true))
    }

    @Test
    fun `a replayed prompt shows what the user typed, without the preamble`() {
        val first = user("h1", TeamText.wrap("You are Lead, the manager of the team \"Crew\".", "fix the build"))
        val next = user("h2", TeamText.wrap("", "and the tests"))
        assertEquals(user("h1", "fix the build"), shownInTeam(first, replayed = true, sentHere = false))
        assertEquals(user("h2", "and the tests"), shownInTeam(next, replayed = true, sentHere = false))
    }

    @Test
    fun `a replayed prompt of the harness is not shown`() {
        val relay = user("h3", TeamText.wrap("${TeamText.RELAY_OPENING} Their reports follow.", "## Backend (done)"))
        val refusal = user("h4", TeamText.wrap("${TeamText.REFUSAL_OPENING} See why.", "Not JSON."))
        assertNull(shownInTeam(relay, replayed = true, sentHere = false))
        assertNull(shownInTeam(refusal, replayed = true, sentHere = false))
    }

    @Test
    fun `other items are shown as they are`() {
        val notice = AgentItem.Notice("n", 1, "Stopped.", false)
        assertEquals(notice, shownInTeam(notice, replayed = true, sentHere = false))
    }

    @Test
    fun `a member without a task is idle`() {
        assertEquals(MemberStatus.Idle, memberStatus(emptyList(), "K#Backend"))
        val others = listOf(task("d1", "Tester", "check", DelegationStatus.Running))
        assertEquals(MemberStatus.Idle, memberStatus(others, "K#Backend"))
    }

    @Test
    fun `a member with a running task works, whatever its earlier tasks`() {
        val items =
            listOf(
                task("d1", "Backend", "one", DelegationStatus.Failed),
                task("d2", "Backend", "two", DelegationStatus.Running),
                task("d3", "Backend", "three", DelegationStatus.Done),
            )
        assertEquals(MemberStatus.Working, memberStatus(items, "K#Backend"))
    }

    @Test
    fun `the status of a member at rest is that of its last task`() {
        val failedLast = listOf(task("d1", "Backend", "one", DelegationStatus.Done), task("d2", "Backend", "two", DelegationStatus.Failed))
        val doneLast = listOf(task("d1", "Backend", "one", DelegationStatus.Failed), task("d2", "Backend", "two", DelegationStatus.Done))
        val stopped = listOf(task("d1", "Backend", "one", DelegationStatus.Cancelled))
        assertEquals(MemberStatus.Failed, memberStatus(failedLast, "K#Backend"))
        assertEquals(MemberStatus.Idle, memberStatus(doneLast, "K#Backend"))
        assertEquals(MemberStatus.Idle, memberStatus(stopped, "K#Backend"))
    }

    @Test
    fun `the work of a task is the part of the member's transcript from its message to the next task`() {
        val first = task("d1", "Backend", "build", DelegationStatus.Done)
        val second = task("d2", "Backend", "test", DelegationStatus.Done)
        val transcript = listOf(user("u1", "build"), answer("a1", "built"), user("u2", "test"), answer("a2", "tested"))
        val delegations = listOf(first, second)
        assertEquals(transcript.take(2), taskItems(transcript, delegations, first))
        assertEquals(transcript.drop(2), taskItems(transcript, delegations, second))
    }

    @Test
    fun `the same task given twice is told apart by its rank`() {
        val first = task("d1", "Backend", "build", DelegationStatus.Done)
        val second = task("d2", "Backend", "build", DelegationStatus.Done)
        val transcript = listOf(user("u1", "build"), answer("a1", "once"), user("u2", "build"), answer("a2", "twice"))
        assertEquals(transcript.drop(2), taskItems(transcript, listOf(first, second), second))
    }

    @Test
    fun `a task that was never given, or whose transcript is gone, has no work`() {
        val given = task("d1", "Backend", "build", DelegationStatus.Cancelled)
        assertEquals(emptyList(), taskItems(emptyList(), listOf(given), given))
        assertEquals(emptyList(), taskItems(listOf(user("u1", "other")), listOf(given), given))
        assertEquals(emptyList(), taskItems(listOf(user("u1", "build")), emptyList(), given))
    }
}
