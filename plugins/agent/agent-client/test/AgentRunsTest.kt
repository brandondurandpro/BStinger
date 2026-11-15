package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRunsTest {
    private fun call(id: String, title: String, kind: AgentToolKind, output: String = "") =
        AgentItem.ToolCall(id, 1, title, kind, AgentToolStatus.Completed, output)

    private val team = AgentTeam("Duo", TeamMember("Lead"), listOf(TeamMember("Coder"), TeamMember("Reviewer")))

    @Test
    fun `a think call is a run named after the member it mentions`() {
        val items = listOf(call("a", "Ask the coder to fix it", AgentToolKind.Think, "done"), call("b", "Read x", AgentToolKind.Read))
        val runs = agentRuns(items, team)
        assertEquals(listOf("Coder"), runs.map { it.name })
        assertEquals("done", runs.single().result)
        assertEquals(listOf("b"), managerCalls(items).map { it.id })
    }

    @Test
    fun `a run no member matches keeps its description, and todo updates are no run`() {
        val items = listOf(call("a", "Explore the repo", AgentToolKind.Think), call("b", "Update TODOs", AgentToolKind.Think))
        val runs = agentRuns(items, null)
        assertEquals(listOf("Explore the repo"), runs.map { it.name })
        assertNull(runs.single().member)
    }
}
