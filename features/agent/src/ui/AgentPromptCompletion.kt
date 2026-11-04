package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.Completing
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.commandsMatching
import org.example.kotlineditor.state.completingAt
import org.example.kotlineditor.state.replaced
import org.example.kotlineditor.model.AgentSlashCommand

private val LIST_HEIGHT = 240.dp

/** A suggestion of the completion of the prompt: what it shows, what it types, what else choosing it does. */
internal class Suggestion(
    /** What it shows first, and its test tag (`/echo`, `src/Main.kt`). */
    val label: String,
    /** What it says after: the description of a command, the directory of a file. */
    val detail: String,
    /** What replaces the word typed. */
    val replacement: String,
    /** What to type after a command, shown greyed. */
    val hint: String? = null,
    val onPick: () -> Unit = {},
)

/**
 * The completion of the prompt of [conversation]: the commands of the agent after a `/` at its start, the files of
 * [files] after an `@`. The arrows move in the list, Enter or Tab chooses (Enter sends a command typed whole), Escape
 * closes it until the word changes.
 */
@Stable
internal class PromptCompletion(
    private val conversation: Conversation,
    private val commands: () -> List<AgentSlashCommand>,
    private val files: (Completing.File) -> List<Suggestion> = { emptyList() },
) {
    var selected by mutableIntStateOf(0)
        private set
    private var dismissed: Completing? by mutableStateOf(null)

    /** The word completed at the caret; null when there is none, or the user closed its list. */
    val completing: Completing? get() = completingAt(conversation.draftValue).takeIf { it != dismissed }

    /** What the list shows. */
    val suggestions: List<Suggestion>
        get() =
            when (val word = completing) {
                is Completing.Command -> commandsMatching(commands(), word.query).map { it.suggestion() }
                is Completing.File -> files(word)
                null -> emptyList()
            }

    /** Handles the keys of the list while it shows; false for the others, and when it does not show. */
    fun handle(event: KeyEvent): Boolean {
        val count = suggestions.size
        if (count == 0 || event.type != KeyEventType.KeyDown) return count > 0 && event.key in KEYS
        when (event.key) {
            Key.DirectionDown -> selected = (selected + 1) % count
            Key.DirectionUp -> selected = (selected - 1 + count) % count
            Key.Enter -> return choosesOnEnter()
            Key.Tab -> pick(selected.coerceAtMost(count - 1))
            Key.Escape -> dismissed = completing
            else -> return false
        }
        return true
    }

    // Enter chooses the suggestion selected, unless it is what is typed already (`/count`): then it sends.
    private fun choosesOnEnter(): Boolean {
        val word = completing ?: return false
        val index = selected.coerceAtMost(suggestions.lastIndex)
        val chosen = suggestions.getOrNull(index)?.replacement?.trimEnd()
        val typed = conversation.draftValue.text.substring(word.start, conversation.draftValue.selection.start)
        if (chosen == typed) return false
        pick(index)
        return true
    }

    /** Chooses the suggestion at [index]: it replaces the word typed. */
    fun pick(index: Int) {
        val word = completing ?: return
        val suggestion = suggestions.getOrNull(index) ?: return
        conversation.draftValue = replaced(conversation.draftValue, word, suggestion.replacement)
        suggestion.onPick()
        selected = 0
    }

    private fun AgentSlashCommand.suggestion() = Suggestion("/$name", description, "/$name ", hint)

    private companion object {
        val KEYS = setOf(Key.DirectionDown, Key.DirectionUp, Key.Enter, Key.Tab, Key.Escape)
    }
}

/** The suggestions of [completion], above the prompt; nothing when there are none. */
@Composable
internal fun CompletionList(completion: PromptCompletion, modifier: Modifier = Modifier) {
    val suggestions = completion.suggestions
    if (suggestions.isEmpty()) return
    val state = rememberLazyListState()
    val selected = completion.selected.coerceAtMost(suggestions.lastIndex)
    LaunchedEffect(selected) { state.scrollToItem(selected) }
    LazyColumn(
        modifier
            .heightIn(max = LIST_HEIGHT)
            .background(IdeColors.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
            .padding(4.dp)
            .testTag(AgentTags.AGENT_COMPLETION),
        state = state,
    ) {
        itemsIndexed(suggestions, key = { _, it -> it.label }) { index, suggestion ->
            SuggestionRow(suggestion, index == selected) { completion.pick(index) }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, selected: Boolean, pick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = pick)
            .testTag(AgentTags.agentCompletion(suggestion.label))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(suggestion.label, fontWeight = FontWeight.Medium)
        suggestion.hint?.let { Label("  $it", color = IdeColors.TextSecondary, fontSize = 12.sp) }
        Spacer(Modifier.width(12.dp))
        Label(suggestion.detail, Modifier.weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}
