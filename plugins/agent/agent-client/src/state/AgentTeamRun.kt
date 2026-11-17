package org.example.kotlineditor.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.model.TeamText

// What a team conversation shows of a run of its team: the manager's messages without their plumbing, the members
// (their status, their transcripts, the permissions they ask for), as read from the transcript of the conversation
// and from those of its members. The server coordinates; nothing here decides what a member does.

/** What a member of a team is doing, as its tab says. */
enum class MemberStatus {
    /** It has no task, or its last one ended well or was stopped. */
    Idle,

    /** It works on a task. */
    Working,

    /** Its last task failed. */
    Failed,
}

/**
 * What a team conversation shows of [item], an item of its manager's transcript; null for nothing. An answer of the
 * manager loses its `team` blocks (the cards say what was delegated) and shows nothing when it was only one. In a
 * history the agent [replayed], a prompt shows what the user typed, without what the harness put before it, and a
 * prompt the harness wrote on its own is not shown. A prompt this window sent ([sentHere]) is always as typed.
 */
internal fun shownInTeam(item: AgentItem, replayed: Boolean, sentHere: Boolean): AgentItem? =
    when (item) {
        is AgentItem.AgentText -> {
            val said = TeamText.withoutBlocks(item.text)
            if (said.isBlank()) null else item.copy(text = said)
        }

        is AgentItem.UserText -> {
            val typed = if (replayed && !sentHere) TeamText.typed(item.text) else item.text
            typed?.let { item.copy(text = it) }
        }

        else -> {
            item
        }
    }

/**
 * What the transcript of a member shows of [item]: its messages without the `ask` block its questions came in (the
 * questions show as cards), and nothing for a message that was only one.
 */
internal fun shownInMember(item: AgentItem): AgentItem? {
    if (item !is AgentItem.AgentText) return item
    val said = TeamText.withoutBlocks(item.text)
    return if (said.isBlank()) null else item.copy(text = said)
}

/** The status of the member whose conversation is [memberKey], from the [items] of the conversation of its team. */
internal fun memberStatus(items: List<AgentItem>, memberKey: String): MemberStatus {
    val tasks = items.filterIsInstance<AgentItem.Delegation>().filter { it.conversation == memberKey }
    val working = tasks.any { it.status == DelegationStatus.Running }
    val last = tasks.lastOrNull()
    return when {
        working -> MemberStatus.Working
        last?.status == DelegationStatus.Failed -> MemberStatus.Failed
        else -> MemberStatus.Idle
    }
}

/**
 * The part of [memberItems], the transcript of a member, that is the work of [delegation]: from the message that gave
 * the task to the next one. A member has one transcript for all its tasks, each given by a message that is the task;
 * [delegations] are those of the team conversation, in order, which tell a task given twice apart. Empty when the
 * task was never given (stopped before), or when the transcript no longer holds it.
 */
internal fun taskItems(
    memberItems: List<AgentItem>,
    delegations: List<AgentItem.Delegation>,
    delegation: AgentItem.Delegation,
): List<AgentItem> {
    val sameTask = delegations.filter { it.conversation == delegation.conversation && it.task == delegation.task }
    val rank = sameTask.indexOfFirst { it.id == delegation.id }
    if (rank < 0) return emptyList()
    val given = memberItems.indices.filter { index -> isTask(memberItems[index], delegation.task) }
    val start = given.getOrNull(rank) ?: return emptyList()
    val after = memberItems.drop(start + 1)
    val length = after.indexOfFirst { it is AgentItem.UserText }
    val work = if (length < 0) after else after.take(length)
    return listOf(memberItems[start]) + work
}

private fun isTask(item: AgentItem, task: String): Boolean = item is AgentItem.UserText && item.text == task

/** The transcript of a member of a team, as far as the IDE read it. */
@Stable
class MemberTranscript internal constructor(
    /** The key of the member's conversation on the server. */
    val key: String,
) {
    /** Its items, oldest first. */
    val items = mutableStateListOf<AgentItem>()
    private val positions = HashMap<String, Int>()

    /** True once the server answered: until then nothing is known of the transcript. */
    var loaded by mutableStateOf(false)
        internal set

    /** False when the server no longer has the transcript (it restarted): the cards keep their reports. */
    var known by mutableStateOf(true)
        internal set

    /** Ids of the first tool call of each run of tool calls the user opened in it. */
    val openedToolRuns = mutableStateMapOf<String, Boolean>()

    /** Scroll position of the member's tab. */
    val listState = LazyListState()

    // Position in the transcript of the server, like a conversation's.
    internal var epoch = ""
    internal var since = 0L

    // The long poll (while a task of the member runs) or the one read (for a card or a tab of a member at rest).
    internal var job: Job? = null
    internal var live = false

    /** Adds [item], or replaces the item with its id. */
    internal fun upsert(item: AgentItem) {
        val position = positions[item.id]
        if (position == null) {
            positions[item.id] = items.size
            items.add(item)
        } else {
            items[position] = item
        }
    }

    /** Forgets the items: the server answers from the start (it restarted). */
    internal fun reset() {
        items.clear()
        positions.clear()
    }
}

/** A permission a member of the team asks the user for. */
class MemberRequest(
    /** The name of the member. */
    val member: String,
    /** The key of its conversation, where the answer goes. */
    val key: String,
    val request: AgentItem.Permission,
)

/**
 * The members of the team of a conversation, as it shows them: the transcripts it follows (those of the members at
 * work, and of the cards and the tab the user opened; no other is kept), the delegation cards the user expanded and
 * the member whose tab is shown.
 */
@Stable
class TeamMembers internal constructor() {
    /** The transcripts followed or read, by the key of the member's conversation. */
    val transcripts = mutableStateMapOf<String, MemberTranscript>()

    /** Ids of the delegations whose card is expanded. */
    val expanded = mutableStateMapOf<String, Boolean>()

    /** The key of the conversation of the member whose tab is shown; null when the conversation itself shows. */
    var shown: String? by mutableStateOf(null)
        internal set

    // Where the work shown in each expanded card is scrolled, by the id of its delegation.
    private val scrolls = HashMap<String, LazyListState>()

    /** The scroll position of the work in the card of the delegation [id], kept while the card is expanded. */
    fun scrollOf(id: String): LazyListState = scrolls.getOrPut(id) { LazyListState() }

    /** Folds the card of the delegation [id] back, or expands it. */
    internal fun toggle(id: String) {
        val wasExpanded = expanded.remove(id) == true
        scrolls.remove(id)
        if (!wasExpanded) expanded[id] = true
    }

    /** Folds every card back and leaves the tab of a member: nobody looks at them any more. */
    internal fun closeViews() {
        expanded.clear()
        scrolls.clear()
        shown = null
    }
}

/** The key of the conversation of [member] in the team conversation [this]. */
fun Conversation.memberKey(member: TeamAgent): String = TeamText.memberConversation(key, member.id)

/** What the member of the conversation [memberKey] is doing. */
fun Conversation.statusOf(memberKey: String): MemberStatus = memberStatus(items, memberKey)

/** The permissions the members at work wait for, in the order of their tasks: several members can ask at once. */
val Conversation.memberRequests: List<MemberRequest>
    get() {
        val running = items.filterIsInstance<AgentItem.Delegation>().filter { it.status == DelegationStatus.Running }
        val asking = running.distinctBy { it.conversation }
        return asking.flatMap { delegation ->
            val transcript = members.transcripts[delegation.conversation]
            val pending = transcript?.items.orEmpty().filterIsInstance<AgentItem.Permission>().filter { it.pending }
            pending.map { MemberRequest(delegation.member, delegation.conversation, it) }
        }
    }

/** The work of [delegation] in the transcript of its member, as far as it was read; empty before. */
fun Conversation.workOf(delegation: AgentItem.Delegation): List<AgentItem> {
    val transcript = members.transcripts[delegation.conversation] ?: return emptyList()
    return taskItems(transcript.items, items.filterIsInstance<AgentItem.Delegation>(), delegation)
}

/**
 * The delegations still shown running end cancelled: the server that ran them is gone (it restarted), nothing will
 * end them. Returns how many there were.
 */
internal fun Conversation.cancelRunningDelegations(): Int {
    val running = items.filterIsInstance<AgentItem.Delegation>().filter { it.status == DelegationStatus.Running }
    for (delegation in running) upsert(delegation.copy(status = DelegationStatus.Cancelled))
    return running.size
}
