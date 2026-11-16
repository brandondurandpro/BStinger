package org.example.kotlineditor.acp

import org.example.kotlineditor.model.AgentItem
import java.util.Collections

/**
 * What the turn running in a conversation says about itself, besides the items it writes: where it started, what the
 * agent answered, the latest error it ran into. A turn that shows no prompt has nothing in the transcript to tell
 * it from the one before, so its text must not continue the answer before it.
 */
internal class TurnLog(private val journal: Journal) {
    private val answered = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var starting = false

    /** Version of the transcript when the current turn started: what is older belongs to the turns before it. */
    @Volatile
    var startVersion = 0L
        private set

    /** The latest error notice of the current turn, null when it had none. */
    @Volatile
    var lastError: String? = null
        private set

    /** A turn starts: its answer is collected apart, and its first text starts an item of its own. */
    fun begin() {
        answered.clear()
        lastError = null
        startVersion = journal.version.value
        starting = true
    }

    /** Whether the text arriving now, [live] unless it is a replay, must start a new item (first of a turn). */
    fun separates(live: Boolean): Boolean {
        val first = starting && live
        if (live) starting = false
        return first
    }

    /** The item [id] holds text of the current turn: of the agent when [agent], live unless it is a replay. */
    fun record(id: String, live: Boolean, agent: Boolean) {
        if (live && agent) answered.add(id)
    }

    /** An error notice was written. */
    fun failed(text: String) {
        lastError = text
    }

    /** The text the agent answered in the current turn: its messages in order, blank line between them. */
    fun answer(): String {
        val ids = synchronized(answered) { answered.toList() }
        val texts = ids.mapNotNull { (journal[it] as? AgentItem.AgentText)?.text?.trim() }
        return texts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
