package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.RelayMember
import org.example.kotlineditor.model.RelayTeam
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentItemCodecsTest {
    private fun call(parent: String?, tool: String?) =
        AgentItem.ToolCall("i1", 1, "Read x", AgentToolKind.Read, AgentToolStatus.Completed, parent = parent, tool = tool)

    private fun roundTrip(item: AgentItem): AgentItem {
        val sent = AgentTranscript(1, "e", true, AgentTurnState.Idle, listOf(item))
        return sent.toJson().agentTranscript().items.single()
    }

    @Test
    fun `the launch a call was made under and its tool name travel with it`() {
        val item = call("e-1", "Read")
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `a call of the manager keeps no parent and no tool`() {
        val item = call(null, null)
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `the conversation of a delegation travels with the call, and an old payload has none`() {
        val item = call(null, "mcp__team__delegate").copy(child = "m~d1")
        assertEquals(item, roundTrip(item))
        val old = AgentTranscript(1, "e", true, AgentTurnState.Idle, listOf(call(null, null))).toJson().toString()
        assertEquals(false, "child" in old)
    }

    @Test
    fun `a relay team travels as JSON`() {
        val team =
            RelayTeam(RelayMember("lead"), listOf(RelayMember("coder", "Be brief.", "Sonnet 5.5", "high"), RelayMember("qa")))
        assertEquals(team, team.toJson().relayTeam())
    }
}
