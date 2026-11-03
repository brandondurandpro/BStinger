package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
                .background(IdeColors.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
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

@Composable
internal fun ToolCallCard(call: AgentItem.ToolCall) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
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
            .background(IdeColors.Editor, RoundedCornerShape(4.dp))
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
    val lines = changedLines(diff.oldText, diff.newText)
    Column(Modifier.fillMaxWidth().background(IdeColors.Input, RoundedCornerShape(4.dp)).padding(6.dp)) {
        Label(
            diff.path.substringAfterLast('/').substringAfterLast('\\'),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        for ((sign, line) in lines.take(DIFF_LINES)) {
            val color = if (sign == '+') IdeColors.GitAdded else IdeColors.Error
            BasicText("$sign $line", style = LocalCodeStyleSmall().copy(color = color), maxLines = 1)
        }
        if (lines.size > DIFF_LINES) {
            Label("… ${lines.size - DIFF_LINES} more lines", color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

/** The removed (`-`) and added (`+`) lines of a change: what differs between the common first and last lines. */
internal fun changedLines(oldText: String?, newText: String): List<Pair<Char, String>> {
    val old = oldText?.lines().orEmpty()
    val new = newText.lines()
    val common = minOf(old.size, new.size)
    val prefix = (0 until common).firstOrNull { old[it] != new[it] } ?: common
    val maxSuffix = common - prefix
    val suffix = (0 until maxSuffix).firstOrNull { old[old.size - 1 - it] != new[new.size - 1 - it] } ?: maxSuffix
    val removed = old.subList(prefix, old.size - suffix).map { '-' to it }
    val added = new.subList(prefix, new.size - suffix).map { '+' to it }
    return removed + added
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
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, if (request.pending) IdeColors.Accent else IdeColors.Separator, RoundedCornerShape(6.dp))
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
