package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.BasePicker
import org.example.kotlineditor.state.BranchChoices

private const val PICKER_WIDTH = 520

// One line of the picker: the branch to start from (null: the project's own), and where it comes from.
private class BaseChoice(val base: String?, val label: String, val kind: String)

/**
 * "Which branch does the agent start from?", asked when a conversation starts in a Git repository: the project's own
 * branch (the agent works in the project), or a local or remote branch (the agent works in a worktree made from it).
 * Escape closes it and no conversation starts.
 */
@Composable
fun BasePickerPopup(agent: AgentState, picker: BasePicker) {
    var query by remember { mutableStateOf("") }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    val choices = picker.choices
    val matches = remember(choices, query) { choices?.let { matching(it, query) }.orEmpty() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { cursor.selected = 0 }
    val choose = { choice: BaseChoice? -> if (choice != null) agent.chooseBase(choice.base) }
    PopupFrame(AgentTags.AGENT_BASE_PICKER, agent::cancelBasePicker, width = PICKER_WIDTH) {
        Column {
            Label("Start from which branch?", fontWeight = FontWeight.SemiBold)
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag(AgentTags.AGENT_BASE_SEARCH)
                    .focusRequester(focus)
                    .popupKeys({ choose(matches.getOrNull(cursor.selected)) }, agent::cancelBasePicker) {
                        cursor.move(it, matches.size)
                    },
                placeholder = "Search branches…",
                leading = { SearchIcon(color = IdeColors.TextSecondary) },
            )
            PickerStatus(agent, picker)
            ResultList(matches, cursor.selected) { index, choice -> BaseRow(choice, index == cursor.selected) { choose(choice) } }
        }
    }
}

@Composable
private fun PickerStatus(agent: AgentState, picker: BasePicker) {
    val failure = picker.failure
    val fetchError = picker.choices?.fetchError
    when {
        failure != null -> {
            Label(failure, Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_ERROR), color = IdeColors.Error, fontSize = 12.sp)
            IdeButton("Start in the project", { agent.chooseBase(null) }, Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_PROJECT))
        }

        fetchError != null -> {
            val text = "The remote branches could not be fetched, the known ones are listed: $fetchError"
            Label(text, Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_FETCH_ERROR), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }

        picker.choices == null -> {
            Label("Reading the branches…", Modifier.padding(top = 8.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }

        picker.fetching -> {
            Label("Fetching the remote branches…", Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_FETCHING), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BaseRow(choice: BaseChoice, selected: Boolean, onClick: () -> Unit) {
    val tag = choice.base?.let(AgentTags::agentBase) ?: AgentTags.AGENT_BASE_PROJECT
    Row(
        Modifier.fillMaxWidth().resultRow(selected).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(choice.label, Modifier.weight(1f))
        Label(choice.kind, color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}

// The project's own branch first, then the local branches, then the remote ones; those the query is in.
private fun matching(choices: BranchChoices, query: String): List<BaseChoice> {
    val current = choices.current
    val own = BaseChoice(null, "${current ?: "Current branch"} (the project itself)", "no worktree")
    val local = choices.local.map { BaseChoice(it, it, "local") }
    val remote = choices.remote.map { BaseChoice(it, it, "remote") }
    val all = listOf(own) + local + remote
    val needle = query.trim()
    return if (needle.isEmpty()) all else all.filter { it.base == null || it.label.contains(needle, ignoreCase = true) }
}
