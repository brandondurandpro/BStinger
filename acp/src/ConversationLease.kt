package org.example.kotlineditor.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.Closeable

/** Why a conversation stopped while a [ConversationLease] was held. */
enum class StopCause {
    /** [AgentService.cancel] stopped it: the user pressed Stop. */
    Cancelled,

    /** [AgentService.close] forgot it. */
    Closed,
}

/** How the turn a prompt started ended. */
enum class TurnEnding {
    /** The agent answered and stopped by itself. */
    Completed,

    /** The conversation was cancelled or closed before the agent finished. */
    Cancelled,

    /** The agent could not answer: its process died, it refused, it ran out of tokens, it reported an error. */
    Failed,
}

/**
 * What a turn came to, as [AgentService.awaitTurn] reports it: how it [ending], what the agent answered ([text], all
 * its messages of the turn in order, blank line between them, trimmed; what it said before a cancellation or a
 * failure is kept) and, for a failed turn, the [reason] (the error notice the transcript shows).
 */
data class TurnOutcome(val ending: TurnEnding, val text: String, val reason: String = "")

/**
 * A conversation kept busy between its turns, so that the transcript never says idle while its holder goes on (for
 * instance while other conversations work for it): a client keeps its Stop button and sees one uninterrupted run.
 * Taken with [AgentService.keepBusy], released with [release] (or [close]) by the holder, who always releases it.
 *
 * While it is held, prompts of other senders wait in the conversation's queue and start once it is released; the
 * holder's own prompts ([AgentService.prompt] with `lease`) start at once, ahead of them.
 */
class ConversationLease internal constructor(private val conversation: ServerConversation) : Closeable {
    private val ended = CompletableDeferred<StopCause>()

    /**
     * Completes when the conversation is cancelled or closed while the lease is held, with the cause: the holder stops
     * what it started and releases the lease. It completes once; the lease stays held until [release].
     */
    val stopped: Deferred<StopCause> get() = ended

    /** Marks [cause]; the first one stays. */
    internal fun stop(cause: StopCause) {
        ended.complete(cause)
    }

    internal fun belongsTo(other: ServerConversation): Boolean = conversation === other

    /** Releases the conversation: it shows idle again, the prompts that waited start; a second release does nothing. */
    fun release() {
        conversation.release(this)
    }

    override fun close() = release()
}
