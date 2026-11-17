package org.example.kotlineditor.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.DelegationStatus

// How a team conversation follows the transcripts of its members, which the server keeps under keys of their own: a
// long poll for each member at work (so that the permissions it asks for show at once), one read for a member at rest
// whose card or tab the user opened, and nothing for the others. A conversation of hundreds of finished delegations
// holds no poll and no transcript until one of its cards opens.

private const val MEMBER_RETRY_MS = 1_000L

/**
 * Follows what [conversation] needs of its members now, and lets go of the rest: called when its transcript changed,
 * when a card or a member's tab opened or closed.
 */
internal fun AgentHost.followMembers(conversation: Conversation) {
    val members = conversation.members
    val delegations = conversation.items.filterIsInstance<AgentItem.Delegation>()
    val working = delegations.filter { it.status == DelegationStatus.Running }.map { it.conversation }.toSet()
    val opened = delegations.filter { members.expanded[it.id] == true }.map { it.conversation }.toSet()
    // A member whose task ended before the server answered once is still read once: its session must be known.
    val unread = members.transcripts.values.filter { !it.loaded }.map { it.key }
    val wanted = working + opened + unread + listOfNotNull(members.shown)
    val dropped = members.transcripts.keys.filter { it !in wanted }
    for (key in dropped) members.transcripts.remove(key)?.job?.cancel()
    for (key in wanted) {
        val transcript = members.transcripts.getOrPut(key) { MemberTranscript(key) }
        follow(conversation, transcript, live = key in working)
    }
}

/** Lets go of every member of [conversation]: it is closed, deleted, or its project closes. */
internal fun Conversation.stopFollowingMembers() {
    for (transcript in members.transcripts.values) transcript.job?.cancel()
    members.transcripts.clear()
    members.closeViews()
}

// A member at work is followed until its task ends; a member at rest is read once. The loop that reads it sees what
// is wanted of it now: the poll of a member that just stopped working ends with its next answer, which is the end of
// its work.
private fun AgentHost.follow(conversation: Conversation, transcript: MemberTranscript, live: Boolean) {
    transcript.live = live
    val reading = transcript.job?.isActive == true
    val nothingToRead = !live && transcript.loaded
    if (reading || nothingToRead) return
    transcript.job = scope.launch { read(conversation, transcript) }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun AgentHost.read(conversation: Conversation, transcript: MemberTranscript) {
    var answered = false
    while (transcript.live || !answered) {
        try {
            val answer = services.agents.transcript(transcript.key, transcript.epoch, transcript.since)
            apply(conversation, transcript, answer)
            answered = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The link is down: read again once it is back, nothing stays loading.
            delay(MEMBER_RETRY_MS)
        }
    }
    // Read to the end of its work: it is let go of now when nothing else needs it.
    followMembers(conversation)
}

private fun AgentHost.apply(conversation: Conversation, transcript: MemberTranscript, answer: AgentTranscript) {
    val restarted = transcript.epoch.isNotEmpty() && transcript.epoch != answer.epoch
    if (restarted) transcript.reset()
    transcript.epoch = answer.epoch
    transcript.since = answer.version
    transcript.known = answer.known
    answer.session?.let(conversation.onMemberSession)
    var wrote = false
    var asked: String? = null
    for (item in answer.items) {
        asked = asked ?: newQuestion(transcript.items, item)
        wrote = wrote || finishedWriting(transcript.items, item)
        transcript.upsert(item)
    }
    transcript.loaded = true
    asked?.let { question -> agentAsking(conversation.title, question) }
    // The tree, the Git panel and the open editors pick up what the member changed, without waiting for the run.
    if (wrote) conversation.workspace?.synchronize()
}
