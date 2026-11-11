package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentItem

// A discussion between two agents: the one of the conversation leads and asks the other, the IDE carries the
// messages from one session to the other, until the first gives the plan they agree on (and then implements it).

/** Questions the leader may ask the other agent before it must give its plan. */
internal const val DISCUSSION_ROUNDS = 5

private val MARKER = Regex("""(?im)^[\s>*_#-]*(ASK|FINAL)[*_]*\s*:[*_]*""")

/** What an agent of the discussion answered: a question for the other, or the plan. */
internal sealed interface DiscussionMove {
    val text: String

    class Ask(override val text: String) : DiscussionMove

    class Final(override val text: String) : DiscussionMove
}

/** The last `ASK:` or `FINAL:` of [answer]; an answer with neither is taken as a question. */
internal fun parseMove(answer: String): DiscussionMove {
    val last = MARKER.findAll(answer).lastOrNull() ?: return DiscussionMove.Ask(answer.trim())
    val text = answer.substring(last.range.last + 1).trim()
    val final = last.groupValues.getOrNull(1).orEmpty().equals("FINAL", ignoreCase = true)
    return if (final) DiscussionMove.Final(text) else DiscussionMove.Ask(text)
}

/**
 * Starts a discussion about [request] between the agent of [conversation] and another one, [entry] of the registry
 * (null: the agent of the settings), in a conversation of its own that works where [conversation] does. The leader
 * asks, the other answers, up to [DISCUSSION_ROUNDS] times; once they agree the leader gives the plan and, when
 * [implement], carries it out. Returns the conversation of the other agent.
 */
fun AgentState.discussWith(conversation: Conversation, entry: String?, request: String, implement: Boolean = true): Conversation {
    if (!conversation.started) conversation.title = titleOf(request)
    val peer = Conversation(nextId++, "Discussion: ${conversation.title}", conversation.branch)
    peer.onTurnIdle = { deliverQueued(peer) }
    // The same branch, so the server hands the worktree of the leader over, not a new one.
    peer.mode = conversation.mode
    chooseAgent(peer, entry, remember = false)
    peer.renamed = true
    conversations.add(0, peer)
    conversations.remove(conversation)
    conversations.add(0, conversation)
    conversation.discussion?.cancel()
    conversation.discussion = project.scope.launch { runDiscussion(conversation, peer, request, implement) }
    return peer
}

private suspend fun AgentState.runDiscussion(leader: Conversation, peer: Conversation, request: String, implement: Boolean) {
    val peerName = agentLabelOf(peer)
    try {
        var promptId = deliverText(leader, openingPrompt(request, peerName))
        for (round in 1..DISCUSSION_ROUNDS + 1) {
            val answer = answerTo(leader, promptId) ?: return stopped(leader, agentLabelOf(leader))
            val move = parseMove(answer)
            if (move is DiscussionMove.Final) return agreed(leader, implement)
            if (round > DISCUSSION_ROUNDS) break
            leader.note("Asking $peerName (question $round of $DISCUSSION_ROUNDS)…", error = false)
            val branch = leader.currentBranch ?: leader.branch
            val peerId = deliverText(peer, peerPrompt(request, move.text, branch, first = round == 1))
            val reply = answerTo(peer, peerId) ?: return stopped(leader, peerName)
            promptId = deliverText(leader, relayPrompt(peerName, reply, last = round == DISCUSSION_ROUNDS))
        }
        leader.note("No plan came out of the discussion after $DISCUSSION_ROUNDS questions.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } finally {
        leader.discussion = null
    }
}

private fun AgentState.agreed(leader: Conversation, implement: Boolean) {
    if (!implement) {
        leader.note("The agents agreed on this plan.", error = false)
        return
    }
    leader.note("The agents agreed: the plan is carried out.", error = false)
    deliverText(leader, "The discussion is over. Implement the final plan now.")
}

private fun stopped(leader: Conversation, who: String) {
    leader.note("The discussion stopped: $who gave no answer.")
}

/** The text the agent of [conversation] answered to the prompt [promptId], once its turn is over; null on a failure. */
private suspend fun answerTo(conversation: Conversation, promptId: String): String? {
    snapshotFlow { answered(conversation, promptId) }.first { it }
    val after = conversation.items.dropWhile { it.id != promptId }.drop(1).takeWhile { it !is AgentItem.UserText }
    if (after.any { it is AgentItem.Notice && it.error }) return null
    val text = after.filterIsInstance<AgentItem.AgentText>().joinToString("\n\n") { it.text }
    return text.takeIf { it.isNotBlank() }
}

// The server has the prompt (its copy replaced the local one) and the agent is no longer busy: its turn is over.
private fun answered(conversation: Conversation, promptId: String): Boolean {
    val prompt = conversation.items.firstOrNull { it.id == promptId } ?: return false
    return prompt.version > 0 && !conversation.busy
}

private fun openingPrompt(request: String, peerName: String): String =
    buildString {
        appendLine(request)
        appendLine()
        appendLine("---")
        appendLine("Work this out with a second agent ($peerName) to find the best, most optimized solution.")
        appendLine("Do not change any file yet. Read the code as much as you need, then end each message with one of:")
        appendLine("ASK: <your question or proposal for the other agent, with what you found in the code>")
        appendLine("FINAL: <the plan you agree on: the files to change, the steps, and why it is the best option>")
        append("Challenge its answers. You may ask up to $DISCUSSION_ROUNDS times; when you agree, give the FINAL plan.")
    }

private fun peerPrompt(request: String, message: String, branch: String, first: Boolean): String =
    buildString {
        if (first) {
            appendLine("You are the second opinion in a design discussion with another agent about this repository (branch `$branch`).")
            appendLine("Do not change any file; you may read files and run read-only commands.")
            appendLine("The user asked:")
            appendLine(request)
            appendLine()
            appendLine("The other agent says:")
        } else {
            appendLine("The other agent replies:")
        }
        appendLine(message)
        appendLine()
        append("Answer it: your view, the better or cheaper alternatives, the risks. Be concrete and concise.")
    }

private fun relayPrompt(peerName: String, reply: String, last: Boolean): String =
    buildString {
        appendLine("$peerName answers:")
        appendLine(reply)
        appendLine()
        if (last) {
            append("That was the last question: end with FINAL: and the plan.")
        } else {
            append("Continue: end with ASK: for another question, or FINAL: and the plan if you agree.")
        }
    }
