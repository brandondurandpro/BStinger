package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem

/** What the user should know of a conversation at a glance. */
enum class AgentOutcome {
    /** Nothing to say: no prompt yet, or the user stopped the agent. */
    Quiet,

    /** The agent works. */
    Working,

    /** The agent waits for the user's answer to a permission request. */
    Asking,

    /** The turn ended with an error, a refusal or a limit. */
    Failed,

    /** The turn ended well and the user did not look yet. */
    Done,

    /** The turn ended well and the user looked. */
    Seen,
}

private const val STOPPED_NOTICE = "Stopped."

/** The [AgentOutcome] of this conversation, from its state and its latest items. */
val Conversation.outcome: AgentOutcome
    get() {
        val asking = items.any { it is AgentItem.Permission && it.pending }
        val last = items.lastOrNull()
        val notice = last as? AgentItem.Notice
        return when {
            asking -> AgentOutcome.Asking
            busy && probeFrom == null -> AgentOutcome.Working
            !finished -> AgentOutcome.Quiet
            notice != null && notice.error -> AgentOutcome.Failed
            notice?.text == STOPPED_NOTICE -> AgentOutcome.Quiet
            seen -> AgentOutcome.Seen
            else -> AgentOutcome.Done
        }
    }
