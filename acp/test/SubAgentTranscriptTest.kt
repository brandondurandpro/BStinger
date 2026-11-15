package org.example.kotlineditor.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the transcript keeps of the calls of a sub-agent, as the Claude adapter sends them. */
class SubAgentTranscriptTest {
    private val journal = Journal("e")
    private val writer = TranscriptWriter(journal)

    // The tests write JSON with single quotes.
    private fun send(json: String) = writer.write(Json.parseToJsonElement(json.replace('\'', '"')).jsonObject)

    private fun calls(): List<AgentItem.ToolCall> = journal.filter { it as? AgentItem.ToolCall }

    private fun launch() =
        send(
            "{'_meta':{'claudeCode':{'toolName':'Agent'}},'toolCallId':'L'," +
                "'sessionUpdate':'tool_call','status':'pending','title':'Task','kind':'think'," +
                "'content':[]}",
        )

    private fun inner(id: String, parent: String?) {
        val stamp = if (parent == null) "" else ",'parentToolUseId':'$parent'"
        send(
            "{'_meta':{'claudeCode':{'toolName':'Read'$stamp}},'toolCallId':'$id'," +
                "'sessionUpdate':'tool_call','status':'pending','title':'Read x','kind':'read'}",
        )
    }

    @Test
    fun `a call stamped with its launch points to the journal item of the launch`() {
        launch()
        inner("C1", "L")
        inner("M1", null)
        val byTitle = calls()
        val launched = byTitle.first { it.tool == "Agent" }
        assertEquals(launched.id, byTitle.first { it.title == "Read x" && it.parent != null }.parent)
        assertNull(byTitle.last().parent)
        assertEquals(3, byTitle.size)
    }

    @Test
    fun `a call that comes before its launch is resolved when the launch appears`() {
        inner("C1", "L")
        assertNull(calls().single().parent)
        launch()
        val items = calls()
        val launched = items.first { it.tool == "Agent" }
        assertEquals(launched.id, items.first { it.tool == "Read" }.parent)
    }

    @Test
    fun `the report handed back is the output of the launch, even when a note follows`() {
        launch()
        send(
            "{'_meta':{'claudeCode':{'toolResponse':{'agentId':'a'," +
                "'handbackReport':{'text':'OK'}}}},'toolCallId':'L'," +
                "'sessionUpdate':'tool_call_update','status':'completed'}",
        )
        send(
            "{'toolCallId':'L','sessionUpdate':'tool_call_update','status':'completed'," +
                "'content':[{'type':'content','content':{'type':'text'," +
                "'text':'report delivered as a message'}}]}",
        )
        val launched = calls().single()
        assertEquals("OK", launched.output)
        assertEquals(AgentToolStatus.Completed, launched.status)
    }

    @Test
    fun `a launch without a report keeps the output of its call`() {
        launch()
        send(
            "{'toolCallId':'L','sessionUpdate':'tool_call_update','status':'completed'," +
                "'content':[{'type':'content','content':{'type':'text','text':'done'}}]}",
        )
        assertEquals("done", calls().single().output)
    }

    private fun delegateCall(id: String, tool: String = "mcp__kotlin-editor-team__delegate") =
        send(
            "{'_meta':{'claudeCode':{'toolName':'$tool'}},'toolCallId':'$id'," +
                "'sessionUpdate':'tool_call','status':'pending','title':'delegate','kind':'other'}",
        )

    @Test
    fun `the conversation of a delegation is recorded on the call the MCP request names`() {
        delegateCall("T1")
        delegateCall("T2")
        writer.attachChild("T2", "m~d1")
        val byCall = calls()
        assertNull(byCall.first().child)
        assertEquals("m~d1", byCall.last().child)
    }

    @Test
    fun `without a tool use id the oldest delegate call with no child gets it`() {
        inner("R1", null)
        delegateCall("T1")
        delegateCall("T2")
        writer.attachChild(null, "m~d1")
        writer.attachChild(null, "m~d2")
        val delegates = calls().filter { it.tool?.endsWith("delegate") == true }
        assertEquals(listOf("m~d1", "m~d2"), delegates.map { it.child })
        assertNull(calls().first { it.tool == "Read" }.child)
    }
}
