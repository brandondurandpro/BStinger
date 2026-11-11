package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import java.util.Locale

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/** What a conversation did, counted from its transcript, its clock and what the agent said of its cost. */
data class ConversationStats(
    /** Prompts of the user. */
    val prompts: Int,
    /** Answers of the agent (its texts). */
    val answers: Int,
    /** Actions of the agent on the project, by kind. */
    val actions: Map<AgentToolKind, Int>,
    /** Actions that failed. */
    val failedActions: Int,
    /** Files the agent changed (distinct). */
    val filesChanged: Int,
    /** Lines the uncommitted changes add and remove now. */
    val linesAdded: Int,
    val linesRemoved: Int,
    /** Time the agent worked, in milliseconds, the turn in progress included. */
    val workedMillis: Long,
    val longestTurnMillis: Long,
    val averageTurnMillis: Long?,
    val cost: Double?,
    val usage: String?,
)

/** The statistics of [conversation] now. */
fun statsOf(conversation: Conversation): ConversationStats {
    val items = conversation.items
    val calls = items.filterIsInstance<AgentItem.ToolCall>()
    val changed = calls.flatMap { call -> call.diffs.map { it.path } }.toSet()
    val running = conversation.workingSince?.let { System.currentTimeMillis() - it } ?: 0L
    val timed = conversation.timedTurns
    val worked = conversation.workedMillis + running
    return ConversationStats(
        prompts = items.count { it is AgentItem.UserText },
        answers = items.count { it is AgentItem.AgentText },
        actions = calls.groupingBy { it.kind }.eachCount(),
        failedActions = calls.count { it.status == AgentToolStatus.Failed },
        filesChanged = changed.size,
        linesAdded = conversation.changes.added,
        linesRemoved = conversation.changes.removed,
        workedMillis = worked,
        longestTurnMillis = maxOf(conversation.longestTurnMillis, running),
        averageTurnMillis = if (timed > 0) conversation.workedMillis / timed else null,
        cost = conversation.costKnown,
        usage = conversation.usage?.let(::usageText),
    )
}

/** `1h 05m`, `3m 12s`, `45s`: a duration, short. */
fun durationText(millis: Long): String =
    when {
        millis >= HOUR -> String.format(Locale.ROOT, "%dh %02dm", millis / HOUR, millis % HOUR / MINUTE)
        millis >= MINUTE -> String.format(Locale.ROOT, "%dm %02ds", millis / MINUTE, millis % MINUTE / SECOND)
        else -> "${millis / SECOND}s"
    }
