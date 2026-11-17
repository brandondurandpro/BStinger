package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.MemberRequest
import org.example.kotlineditor.state.MemberStatus
import org.example.kotlineditor.state.MemberTranscript
import org.example.kotlineditor.state.memberKey
import org.example.kotlineditor.state.statusOf
import org.example.kotlineditor.state.workOf

// What a team conversation shows of its members: a card per task the manager delegated, the permissions the members
// ask for, and the view of a member that its tab shows.

// The work of a member in a card scrolls in place beyond this height: the card never takes the whole conversation.
private val WORK_HEIGHT = 320.dp

// The report of a member shows at most these lines in its card; the rest is in its work.
private const val REPORT_LINES = 12

/**
 * A task the manager gave a member: who, what and how far it got; expanded, the report of the member and its work,
 * the part of its transcript that is this task, which a running card follows live.
 */
@Composable
internal fun DelegationCard(agent: AgentState, conversation: Conversation, delegation: AgentItem.Delegation) {
    val expanded = conversation.members.expanded[delegation.id] == true
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, IdeShapes.Large)
            .border(1.dp, IdeColors.Separator, IdeShapes.Large)
            .testTag(AgentTags.agentDelegation(delegation.id)),
    ) {
        DelegationHeader(delegation, expanded) { agent.toggleDelegation(conversation, delegation) }
        if (expanded) DelegationDetails(conversation, delegation)
    }
}

@Composable
private fun DelegationHeader(delegation: AgentItem.Delegation, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag(AgentTags.agentDelegationToggle(delegation.id))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(if (expanded) "▾" else "▸", color = IdeColors.TextSecondary, fontSize = 12.sp)
        Box(Modifier.padding(start = 8.dp).size(8.dp).background(colorOf(delegation.status), CircleShape))
        Label(delegation.member, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        BasicText(
            delegation.task.lineSequence().firstOrNull().orEmpty(),
            Modifier.padding(start = 8.dp).weight(1f),
            style = bodyStyle.copy(color = IdeColors.TextSecondary),
            maxLines = 1,
        )
        Label(
            wordOf(delegation.status),
            Modifier.padding(start = 8.dp).testTag(AgentTags.agentDelegationStatus(delegation.id)),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

// The report first: it is in the card itself, and shows before the work of the member is read.
@Composable
private fun DelegationDetails(conversation: Conversation, delegation: AgentItem.Delegation) {
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (delegation.result.isNotBlank()) {
            val failed = delegation.status == DelegationStatus.Failed
            BasicText(
                delegation.result.trim(),
                Modifier.fillMaxWidth().testTag(AgentTags.agentDelegationReport(delegation.id)),
                style = bodyStyle.copy(color = if (failed) IdeColors.Error else IdeColors.Text),
                maxLines = REPORT_LINES,
            )
        }
        val transcript = conversation.members.transcripts[delegation.conversation]
        val rows by remember(conversation, delegation) { derivedStateOf { transcriptRows(conversation.workOf(delegation)) } }
        val note = workNote(delegation, transcript, rows.isEmpty())
        if (note != null) {
            val tag = AgentTags.agentDelegationNote(delegation.id)
            Label(note, Modifier.testTag(tag), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        if (transcript != null && rows.isNotEmpty()) {
            val scroll = conversation.members.scrollOf(delegation.id)
            val modifier = Modifier.fillMaxWidth().heightIn(max = WORK_HEIGHT).testTag(AgentTags.agentDelegationWork(delegation.id))
            // The request of a member is answered at the end of the conversation: one place, whatever card is open.
            MemberRows(rows, transcript, delegation.member, scroll, modifier, onAnswer = null)
        }
    }
}

// What an expanded card says while it has no work to show; null when it shows it.
private fun workNote(delegation: AgentItem.Delegation, transcript: MemberTranscript?, empty: Boolean): String? {
    val member = delegation.member
    val running = delegation.status == DelegationStatus.Running
    return when {
        transcript == null || !transcript.loaded -> "Reading the work of $member…"
        !empty -> null
        running -> "$member has not started this task yet."
        !transcript.known -> "The work of $member on this task is no longer on the server."
        else -> "$member did not work on this task."
    }
}

/**
 * The rows of the work of a member, virtualized like the transcript of a conversation: a long one composes only what
 * shows. Its permission requests are answered with [onAnswer], or elsewhere when it is null.
 */
@Composable
internal fun MemberRows(
    rows: List<TranscriptRow>,
    transcript: MemberTranscript,
    member: String,
    state: LazyListState,
    modifier: Modifier,
    onAnswer: ((AgentItem.Permission, String) -> Unit)?,
) {
    SelectionContainer {
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(AgentTags.agentMemberRow(index))) {
                    when (row) {
                        is TranscriptRow.Single -> PlainItemView(row.item, member, onAnswer)
                        is TranscriptRow.Tools -> ToolCallRun(transcript.openedToolRuns, row.calls)
                    }
                }
            }
        }
    }
}

/** The permission a member asks for, with the usual buttons: the answer goes to the member's own conversation. */
@Composable
internal fun MemberRequestCard(agent: AgentState, conversation: Conversation, asked: MemberRequest) {
    PermissionCard(asked.request, asked.member) { option -> agent.answer(conversation, asked.request, option, asked.key) }
}

/**
 * What the tab of a member shows in place of the conversation: who it is, what it is doing, and its transcript, every
 * task it was given in this conversation.
 */
@Composable
fun MemberView(agent: AgentState, conversation: Conversation, memberKey: String) {
    val member = conversation.team?.team?.members?.firstOrNull { conversation.memberKey(it) == memberKey }
    val name = member?.name ?: nameInDelegations(conversation, memberKey)
    val transcript = conversation.members.transcripts[memberKey]
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).testTag(AgentTags.AGENT_MEMBER_VIEW)) {
        MemberHeader(name, member, conversation.statusOf(memberKey))
        val rows by remember(transcript) { derivedStateOf { transcriptRows(transcript?.items.orEmpty()) } }
        val note = memberNote(name, transcript, rows.isEmpty())
        if (note != null) {
            Label(note, Modifier.padding(top = 12.dp).testTag(AgentTags.AGENT_MEMBER_EMPTY), color = IdeColors.TextSecondary)
        }
        if (transcript != null && rows.isNotEmpty()) {
            val modifier = Modifier.fillMaxSize().testTag(AgentTags.AGENT_MEMBER_TRANSCRIPT)
            // Only this view shows while the tab is selected: the request of the member is answered here.
            MemberRows(rows, transcript, name, transcript.listState, modifier) { request, option ->
                agent.answer(conversation, request, option, memberKey)
            }
        }
    }
}

@Composable
private fun MemberHeader(name: String, member: TeamAgent?, status: MemberStatus) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colorOf(status), CircleShape))
        Label(name, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        Label(
            wordOf(status),
            Modifier.padding(start = 8.dp).testTag(AgentTags.AGENT_MEMBER_STATUS),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        val role = member?.description.orEmpty().lineSequence().firstOrNull().orEmpty()
        Label(role, Modifier.padding(start = 12.dp).weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

// What the view of a member says while it has no transcript to show; null when it shows it.
private fun memberNote(name: String, transcript: MemberTranscript?, empty: Boolean): String? =
    when {
        transcript == null || !transcript.loaded -> "Reading the work of $name…"
        !empty -> null
        else -> "$name has no task in this conversation yet."
    }

// The name the manager gave the member of [memberKey] a task under, when its team no longer says.
private fun nameInDelegations(conversation: Conversation, memberKey: String): String {
    val delegations = conversation.items.filterIsInstance<AgentItem.Delegation>()
    return delegations.firstOrNull { it.conversation == memberKey }?.member ?: "Member"
}

internal fun colorOf(status: MemberStatus): Color =
    when (status) {
        MemberStatus.Idle -> IdeColors.TextSecondary
        MemberStatus.Working -> IdeColors.Accent
        MemberStatus.Failed -> IdeColors.Error
    }

internal fun wordOf(status: MemberStatus): String =
    when (status) {
        MemberStatus.Idle -> "idle"
        MemberStatus.Working -> "working"
        MemberStatus.Failed -> "failed"
    }

private fun colorOf(status: DelegationStatus): Color =
    when (status) {
        DelegationStatus.Running -> IdeColors.Accent
        DelegationStatus.Done -> IdeColors.GitAdded
        DelegationStatus.Failed -> IdeColors.Error
        DelegationStatus.Cancelled -> IdeColors.TextSecondary
    }

private fun wordOf(status: DelegationStatus): String =
    when (status) {
        DelegationStatus.Running -> "running…"
        DelegationStatus.Done -> "done"
        DelegationStatus.Failed -> "failed"
        DelegationStatus.Cancelled -> "cancelled"
    }
