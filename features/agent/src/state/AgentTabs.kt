package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Conversations open in the tabs of agent mode; closing the active one selects its right neighbour. */
@Stable
class AgentTabs {
    val open = mutableStateListOf<Conversation>()

    var active: Conversation? by mutableStateOf(null)
        private set

    fun open(conversation: Conversation) {
        if (conversation !in open) {
            val at = active?.let { open.indexOf(it) + 1 } ?: open.size
            open.add(at, conversation)
        }
        active = conversation
    }

    fun close(conversation: Conversation) {
        val index = open.indexOf(conversation)
        if (index < 0) return
        open.removeAt(index)
        if (active == conversation) active = open.getOrNull(index) ?: open.lastOrNull()
    }

    fun selectNext(step: Int = 1) {
        val current = open.indexOf(active)
        if (open.isEmpty() || current < 0) return
        active = open[(current + step).mod(open.size)]
    }
}
