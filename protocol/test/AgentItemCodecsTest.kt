package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.model.DelegationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentItemCodecsTest {
    private fun transcriptOf(vararg items: AgentItem) = AgentTranscript(7, "epoch", true, AgentTurnState.Running, items.toList())

    @Test
    fun `a delegation travels in each of its statuses`() {
        for (status in DelegationStatus.entries) {
            val item = AgentItem.Delegation("d-$status", 3, "Backend", "c1#backend", "write the api", status, "report of $status")
            val transcript = transcriptOf(item)
            assertEquals(transcript, transcript.toJson().agentTranscript())
        }
    }

    @Test
    fun `a delegation without a result is read with an empty one`() {
        val item = AgentItem.Delegation("d1", 1, "Tester", "c1#tester", "run the tests\nand report", DelegationStatus.Running)
        val read = transcriptOf(item).toJson().agentTranscript()
        assertEquals(listOf<AgentItem>(item), read.items)
    }

    @Test
    fun `a delegation travels among the other items`() {
        val items =
            arrayOf(
                AgentItem.UserText("u1", 1, "build it"),
                AgentItem.Delegation("d1", 2, "Backend", "c1#b", "task", DelegationStatus.Done, "done"),
                AgentItem.Notice("n1", 3, "Stopped.", false),
            )
        val transcript = transcriptOf(*items)
        assertEquals(transcript, transcript.toJson().agentTranscript())
    }
}
