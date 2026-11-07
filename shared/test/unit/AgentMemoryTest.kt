package org.example.kotlineditor.unit

import org.example.kotlineditor.state.AgentMemory
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The agents the IDE remembers per session, for the conversations of the history: saved, and the recent ones kept. */
class AgentMemoryTest {
    private val store = InMemoryPreferenceStore()
    private val memory = memoryOver(store)

    // A memory over [store], so what one IDE runs, the next IDE reads.
    private fun memoryOver(store: PreferenceStore): AgentMemory =
        AgentMemory({ store.load()[it] }) { key, value -> store.save(store.load() + (key to value)) }

    @Test
    fun `an agent remembered for a session says which one it ran with`() {
        memory.remember("session-1", "claude-acp")
        assertEquals("claude-acp", memory.agentOf("session-1"))
    }

    @Test
    fun `a session that ran the agent of the settings is remembered as none`() {
        memory.remember("session-1", null)
        assertNull(memory.agentOf("session-1"))
        assertNull(AgentMemory().agentOf("session-1"))
    }

    @Test
    fun `nothing is remembered for an unknown session`() {
        memory.remember("session-1", "claude-acp")
        assertNull(memory.agentOf("session-2"))
    }

    @Test
    fun `what one IDE ran, the next IDE reads`() {
        val store = InMemoryPreferenceStore()
        memoryOver(store).remember("session-1", "claude-acp")
        val next = memoryOver(store)
        assertEquals("claude-acp", next.agentOf("session-1"))
        assertEquals(listOf("claude-acp"), next.registries)
    }

    @Test
    fun `the agents remembered are named once, the recent first`() {
        memory.remember("session-1", "claude-acp")
        memory.remember("session-2", "codex-acp")
        memory.remember("session-3", "claude-acp")
        assertEquals(listOf("claude-acp", "codex-acp"), memory.registries)
    }

    @Test
    fun `the oldest sessions are dropped`() {
        repeat(AgentMemory.KEPT) { index -> memory.remember("session-$index", "agent-$index") }
        memory.remember("session-new", "new-agent")
        assertNull(memory.agentOf("session-0"))
        assertEquals("new-agent", memory.agentOf("session-new"))
    }

    @Test
    fun `a session run again with another agent replaces its memory`() {
        memory.remember("session-1", "claude-acp")
        memory.remember("session-1", "codex-acp")
        assertEquals("codex-acp", memory.agentOf("session-1"))
        assertEquals(listOf("codex-acp"), memory.registries)
    }
}
