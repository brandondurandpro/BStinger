package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState

// How a conversation follows its transcript on the server: a long poll, each answer newer than what it shows, so a
// reconnection loses nothing and a restarted server is noticed.

private const val RETRY_MS = 1_000L

// Tool calls whose end changes files: the tree, Git and the open editors look at the disk again.
private val WRITING_TOOLS = setOf(AgentToolKind.Edit, AgentToolKind.Delete, AgentToolKind.Move, AgentToolKind.Execute)

// Follows the transcript of [conversation] on the server, once: every answer is newer than what it shows.
internal fun AgentHost.followTranscript(conversation: Conversation) {
    if (conversation.polling?.isActive == true) return
    conversation.polling = scope.launch { poll(conversation) }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun AgentHost.poll(conversation: Conversation) {
    while (true) {
        try {
            val transcript = services.agents.transcript(conversation.key, conversation.epoch, conversation.since)
            apply(conversation, transcript)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delay(RETRY_MS)
        }
    }
}

private fun AgentHost.apply(conversation: Conversation, transcript: AgentTranscript) {
    val restarted = conversation.epoch.isNotEmpty() && conversation.epoch != transcript.epoch
    conversation.epoch = transcript.epoch
    conversation.since = transcript.version
    if (!transcript.known) {
        forgotten(conversation, restarted)
        return
    }
    val wasBusy = conversation.state != AgentTurnState.Idle
    transcript.session?.let { conversation.session = it }
    if (!conversation.renamed) transcript.title?.let { conversation.title = titleOf(it) }
    transcript.usage?.let { conversation.usage = it }
    if (transcript.commands.isNotEmpty()) conversation.commands = transcript.commands
    // A choice on its way shows until the server has it.
    if (transcript.options.isNotEmpty()) conversation.serverOptions = transcript.options
    val settled = conversation.settingOptions == 0
    if (settled && transcript.options.isNotEmpty()) conversation.options = transcript.options
    // The history the agent replayed replaces what the conversation showed, once per server.
    if (transcript.replayed && conversation.replayedEpoch != transcript.epoch) {
        conversation.replayedEpoch = transcript.epoch
        conversation.replaceByHistory()
    }
    if (transcript.items.isNotEmpty() && !transcript.replayed) conversation.lastMessageAt = System.currentTimeMillis()
    var wrote = false
    var asked: String? = null
    for (item in transcript.items) {
        asked = asked ?: newQuestion(conversation, item)
        wrote = wrote || finishedWriting(conversation, item)
        if (item is AgentItem.AgentText) quotaLimitsOf(item.text).takeIf { it.isNotEmpty() }?.let { conversation.quota = it }
        conversation.upsert(item)
    }
    asked?.let { question -> agentAsking(conversation.title, question) }
    conversation.state = transcript.state
    conversation.syncWorkingClock()
    transcript.agentName?.let { conversation.agentName = it }
    // The turn is over the way the server said it: a prompt queued while it answered goes now.
    if (transcript.state == AgentTurnState.Idle) conversation.onTurnIdle?.invoke()
    val turnEnded = wasBusy && transcript.state == AgentTurnState.Idle
    if (turnEnded) {
        conversation.finished = true
        conversation.seen = false
        agentFinished(conversation.title, conversation.outcome == AgentOutcome.Failed)
    }
    // The tree, the Git panel and the open editors pick the changes of the agent up.
    if (wrote || turnEnded) conversation.workspace?.synchronize()
}

// The server does not know the conversation: it restarted. The next prompt reopens the session of the agent, which
// keeps it; a conversation without one is lost. A prompt queued meanwhile goes once the server is back.
private fun forgotten(conversation: Conversation, restarted: Boolean) {
    val wasBusy = conversation.state != AgentTurnState.Idle
    conversation.state = AgentTurnState.Idle
    conversation.syncWorkingClock()
    conversation.onTurnIdle?.invoke()
    val shownByServer = conversation.items.any { it.version > 0 }
    val lost = shownByServer && conversation.session == null
    if (restarted && lost) {
        conversation.note("The server restarted: the agent no longer remembers this conversation.", error = false)
    } else if (wasBusy) {
        conversation.note("The agent stopped.")
    }
}

private fun finishedWriting(conversation: Conversation, item: AgentItem): Boolean {
    if (item !is AgentItem.ToolCall || item.kind !in WRITING_TOOLS) return false
    val done = item.status == AgentToolStatus.Completed || item.status == AgentToolStatus.Failed
    val before = conversation.items.firstOrNull { it.id == item.id } as? AgentItem.ToolCall
    return done && before?.status != item.status
}

// What a permission request that just appeared asks, null for any other item or a request already shown.
private fun newQuestion(conversation: Conversation, item: AgentItem): String? {
    if (item !is AgentItem.Permission || !item.pending) return null
    val before = conversation.items.firstOrNull { it.id == item.id } as? AgentItem.Permission
    return item.title.takeIf { before?.pending != true }
}
