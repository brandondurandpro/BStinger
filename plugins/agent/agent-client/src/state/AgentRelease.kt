package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import java.io.IOException

// What a conversation holds while no tab shows it: nothing, when its agent can replay it. Its items (here, and in the
// journal of the server) and its session in the agent process weigh megabytes each, and the conversations of a
// working day are many: only those shown are kept, the others open again as a conversation of the history does.

// Whether nothing would be lost: the agent replays all of it, and nothing is under way.
private val Conversation.releasable: Boolean
    get() {
        val resting = !busy && queued.isEmpty() && probeFrom == null && settingOptions == 0
        val alone = discussion?.isActive != true
        val kept = session != null && replayable && items.isNotEmpty()
        return resting && alone && kept
    }

/** Releases what [conversation], whose tab closed, holds in memory here and on the server, when nothing is lost. */
internal fun AgentState.release(conversation: Conversation) {
    val directory = conversation.workspace?.root?.path
    if (directory == null || !conversation.releasable) return
    conversation.polling?.cancel()
    conversation.polling = null
    conversation.forget(directory)
    val agents = project.services.agents
    conversation.releasing =
        project.scope.launch {
            try {
                agents.close(conversation.key)
                conversation.closedOnServer = true
            } catch (_: IOException) {
                // The server is told again before the conversation opens.
            }
        }
}
