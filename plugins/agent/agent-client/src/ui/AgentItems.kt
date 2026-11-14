package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentDiff
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTerminalOutput
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.Conversation

// The items of a conversation: the prompts, thoughts, tool calls with their diffs, plans, permission requests and
// notices.

// The diff of a tool call shows at most these lines; the rest opens from the Git panel.
private const val DIFF_LINES = 12

// The output of a tool call, or a thought, shows at most these lines.
private const val OUTPUT_LINES = 6

// Lines shown of the output of a command: its end, where the result and the errors are.
private const val TERMINAL_LINES = 12

@Composable
internal fun UserBubble(text: String, context: List<String> = emptyList()) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        BasicText(
            text,
            Modifier
                .widthIn(max = 560.dp)
                .background(IdeColors.Panel, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = bodyStyle,
        )
        SentContext(context, Modifier.widthIn(max = 560.dp).padding(top = 4.dp))
    }
}

@Composable
internal fun ThoughtView(text: String) {
    BasicText(
        text.trim(),
        Modifier.fillMaxWidth().padding(start = 8.dp),
        style = bodyStyle.copy(color = IdeColors.TextSecondary, fontStyle = FontStyle.Italic, fontSize = 12.sp),
        maxLines = OUTPUT_LINES,
    )
}

@Composable
internal fun NoticeView(notice: AgentItem.Notice) {
    BasicText(
        notice.text,
        Modifier.fillMaxWidth(),
        style =
        bodyStyle.copy(
            color = if (notice.error) IdeColors.Error else IdeColors.TextSecondary,
            fontSize = 12.sp,
        ),
    )
}

// A row of the transcript: one item, or the tool calls the agent made one after the other.
internal sealed interface TranscriptRow {
    val key: String

    data class Single(val item: AgentItem) : TranscriptRow {
        override val key get() = item.id
    }

    data class Tools(val calls: List<AgentItem.ToolCall>) : TranscriptRow {
        override val key get() = calls.firstOrNull()?.id.orEmpty()
    }
}

internal fun transcriptRows(items: List<AgentItem>): List<TranscriptRow> {
    val rows = ArrayList<TranscriptRow>()
    var run = ArrayList<AgentItem.ToolCall>()
    for (item in items) {
        if (item is AgentItem.ToolCall) {
            run.add(item)
            continue
        }
        if (run.isNotEmpty()) rows.add(TranscriptRow.Tools(run))
        run = ArrayList()
        rows.add(TranscriptRow.Single(item))
    }
    if (run.isNotEmpty()) rows.add(TranscriptRow.Tools(run))
    return rows
}

// The commands of the agent are folded behind a link, with the last one beside it to follow what it does.
@Composable
internal fun ToolCallRun(conversation: Conversation, calls: List<AgentItem.ToolCall>) {
    val first = calls.firstOrNull() ?: return
    val opened = conversation.openedToolRuns[first.id] == true
    val noun = if (calls.size == 1) "command" else "commands"
    val verb = if (opened) "Hide" else "Show"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                "$verb ${calls.size} $noun",
                Modifier
                    .clickable { conversation.openedToolRuns[first.id] = !opened }
                    .testTag(AgentTags.AGENT_TOOLS_TOGGLE),
                color = IdeColors.Accent,
                fontSize = 12.sp,
            )
            val last = calls.lastOrNull()
            if (!opened && last != null) {
                BasicText(
                    last.title,
                    Modifier.padding(start = 8.dp).weight(1f),
                    style = LocalCodeStyleSmall().copy(color = IdeColors.TextSecondary),
                    maxLines = 1,
                )
            }
        }
        if (opened) {
            for (call in calls) ToolCallCard(call)
        }
    }
}

@Composable
internal fun ToolCallCard(call: AgentItem.ToolCall) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, IdeShapes.Large)
            .border(1.dp, IdeColors.Separator, IdeShapes.Large)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor(call.status), CircleShape))
            Label(call.kind.name, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            BasicText(
                call.title,
                Modifier.padding(start = 8.dp).weight(1f),
                style = LocalCodeStyleSmall(),
                maxLines = 1,
            )
            Label(
                statusText(call.status),
                Modifier.padding(start = 8.dp),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        if (call.output.isNotBlank()) {
            BasicText(call.output.trimEnd(), style = LocalCodeStyleSmall(), maxLines = OUTPUT_LINES)
        }
        call.terminal?.let { TerminalOutput(it) }
        for (diff in call.diffs) DiffPreview(diff)
    }
}

// The end of the output of a command the agent runs, as a terminal shows it, and how it exited.
@Composable
private fun TerminalOutput(terminal: AgentTerminalOutput) {
    val lines = terminal.text.trimEnd().lines()
    val end = lines.takeLast(TERMINAL_LINES)
    val hidden = terminal.truncated || lines.size > end.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Editor, IdeShapes.Medium)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(AgentTags.AGENT_TERMINAL),
    ) {
        if (hidden) Label("…", color = IdeColors.TextSecondary, fontSize = 12.sp)
        BasicText(end.joinToString("\n"), style = LocalCodeStyleSmall())
        val code = terminal.exitCode
        if (code != null) {
            val color = if (code == 0) IdeColors.TextSecondary else IdeColors.Error
            Label("Exit code $code", color = color, fontSize = 12.sp)
        }
    }
}

private fun statusColor(status: AgentToolStatus) =
    when (status) {
        AgentToolStatus.Pending, AgentToolStatus.Running -> IdeColors.Accent
        AgentToolStatus.Completed -> IdeColors.GitAdded
        AgentToolStatus.Failed -> IdeColors.Error
    }

private fun statusText(status: AgentToolStatus) =
    when (status) {
        AgentToolStatus.Pending -> "pending"
        AgentToolStatus.Running -> "running…"
        AgentToolStatus.Completed -> "done"
        AgentToolStatus.Failed -> "failed"
    }

// The lines a change removes and adds, between the lines it keeps at both ends.
@Composable
private fun DiffPreview(diff: AgentDiff) {
    val lines = diff.removed.map { '-' to it } + diff.added.map { '+' to it }
    val more = lines.size - DIFF_LINES + diff.hiddenLines
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Input, IdeShapes.Medium)
            .padding(6.dp)
            .testTag(AgentTags.AGENT_DIFF),
    ) {
        Label(
            diff.path.substringAfterLast('/').substringAfterLast('\\'),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        for ((sign, line) in lines.take(DIFF_LINES)) {
            val color = if (sign == '+') IdeColors.GitAdded else IdeColors.Error
            BasicText("$sign $line", style = LocalCodeStyleSmall().copy(color = color), maxLines = 1)
        }
        if (more > 0) {
            Label(
                "… $more more lines",
                Modifier.testTag(AgentTags.AGENT_DIFF_MORE),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun PlanView(plan: AgentItem.Plan) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("Plan", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        for (entry in plan.entries) {
            val mark =
                when (entry.status) {
                    "completed" -> "✓"
                    "in_progress" -> "▸"
                    else -> "○"
                }
            val color = if (entry.status == "completed") IdeColors.TextSecondary else IdeColors.Text
            Label("$mark  ${entry.content}", color = color, fontSize = 12.sp, maxLines = 2)
        }
    }
}

// The agent asks before it acts: one button per option while it waits, then the answer.
@Composable
internal fun PermissionCard(request: AgentItem.Permission, onAnswer: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, IdeShapes.Large)
            .border(1.dp, if (request.pending) IdeColors.Accent else IdeColors.Separator, IdeShapes.Large)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("The agent asks to: ${request.title}", fontWeight = FontWeight.SemiBold, maxLines = 3)
        if (request.pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in request.options) {
                    IdeButton(
                        option.name,
                        { onAnswer(option.id) },
                        Modifier.testTag(AgentTags.agentPermission(option.id)),
                        primary = option.kind.startsWith("allow"),
                    )
                }
            }
        } else {
            val chosen = request.options.firstOrNull { it.id == request.chosen }?.name
            val answer = if (request.cancelled) "Cancelled" else "Answered: $chosen"
            Label(answer, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}
