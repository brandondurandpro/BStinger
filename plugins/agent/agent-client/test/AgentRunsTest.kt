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

    private fun launch(id: String, tool: String) =
        AgentItem.ToolCall(id, 1, "Task", AgentToolKind.Think, AgentToolStatus.Completed, tool = tool)

    private fun inner(id: String, parent: String?) =
        AgentItem.ToolCall(id, 1, "Read x", AgentToolKind.Read, AgentToolStatus.Completed, parent = parent, tool = "Read")

    @Test
    fun `the calls of a sub-agent are nested in its run and not the manager's`() {
        val items = listOf(launch("l1", "Agent"), inner("c1", "l1"), inner("c2", "l1"), inner("m1", null))
        val run = agentRuns(items, null).single()
        assertEquals(listOf("c1", "c2"), nestedCalls(items, run).map { it.id })
        assertEquals(listOf("m1"), managerCalls(items).map { it.id })
    }

    @Test
    fun `a launch is told by its tool name, Agent or Task`() {
        val items = listOf(launch("l1", "Agent"), launch("l2", "Task"), inner("m1", null))
        assertEquals(listOf("l1", "l2"), agentRuns(items, null).map { it.id })
    }

    @Test
    fun `a think call of another tool is no launch when the tool is known`() {
        val items = listOf(launch("t1", "TodoWrite"))
        assertEquals(emptyList(), agentRuns(items, null))
    }

    @Test
    fun `a run without recorded calls has none nested, as after a reload`() {
        val items = listOf(launch("l1", "Agent"))
        val run = agentRuns(items, null).single()
        assertEquals(emptyList(), nestedCalls(items, run))
    }

    private fun delegate(id: String, title: String, child: String?) =
        AgentItem.ToolCall(id, 1, title, AgentToolKind.Other, AgentToolStatus.Completed, "report", tool = "mcp__kotlin-editor-team__delegate", child = child)

    @Test
    fun `a delegate call of the relay is a run that follows its child, and not a call of the manager`() {
        val items = listOf(delegate("d", "delegate Coder", "m~d1"), call("b", "Read x", AgentToolKind.Read))
        val run = agentRuns(items, team).single()
        assertEquals("Coder", run.name)
        assertEquals("m~d1", run.child)
        assertEquals(true, run.relayed)
        assertEquals(listOf("b"), managerCalls(items).map { it.id })
    }

    @Test
    fun `a delegate call whose member is not known yet is a relayed run without a child`() {
        val run = agentRuns(listOf(delegate("d", "delegate", null)), team).single()
        assertNull(run.child)
        assertEquals(true, run.relayed)
    }
}
