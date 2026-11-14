package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
private val STATUS_HEIGHT = 18.dp

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
    val projects = agent.project.otherProjects
    val canChooseProject = agent.project.canStartInOtherProject
    val matches = remember(choices, query) { choices?.let { matching(it, query) }.orEmpty() }
    val projectMatches = remember(projects, query) { matchingProjects(projects, query) }
    // The remote's default branch (`origin/main`) is highlighted first, so that Enter starts from it; the project's own
    // line when there is none or the query leaves it out.
    val defaultRemote = choices?.defaultRemote
    LaunchedEffect(picker.choosingProject) { focus.requestFocus() }
    LaunchedEffect(query, defaultRemote, picker.choosingProject) {
        val defaultIndex = matches.indexOfFirst { it.base != null && it.base == defaultRemote }
        cursor.selected = if (picker.choosingProject) 0 else maxOf(defaultIndex, 0)
    }
    val choose = { choice: BaseChoice? -> if (choice != null) agent.chooseBase(choice.base) }
    val chooseProject = { path: String? -> if (path != null) agent.chooseProject(path) }
    val shown = if (picker.choosingProject) projectMatches.size else matches.size
    val accept = {
        val index = cursor.selected
        if (picker.choosingProject) chooseProject(projectMatches.getOrNull(index)) else choose(matches.getOrNull(index))
    }
    PopupFrame(AgentTags.AGENT_BASE_PICKER, agent::cancelBasePicker, width = PICKER_WIDTH) {
        Column {
            Label(if (picker.choosingProject) "Start in which project?" else "Start from which branch?", fontWeight = FontWeight.SemiBold)
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag(AgentTags.AGENT_BASE_SEARCH)
                    .focusRequester(focus)
                    .popupKeys(accept, agent::cancelBasePicker) { cursor.move(it, shown) },
                placeholder = if (picker.choosingProject) "Search projects…" else "Search branches…",
                leading = { SearchIcon(color = IdeColors.TextSecondary) },
            )
            if (picker.choosingProject) {
                BrowseButton(agent::browseProject)
                ResultList(projectMatches, cursor.selected) { index, path ->
                    ProjectRow(path, index == cursor.selected) { chooseProject(path) }
                }
            } else {
                PickerStatus(agent, picker)
                OtherProjectButton(canChooseProject) {
                    query = ""
                    picker.choosingProject = true
                }
                ResultList(matches, cursor.selected) { index, choice -> BaseRow(choice, index == cursor.selected) { choose(choice) } }
            }
        }
    }
}

// A directory that is no recent project, perhaps still to create: the Open Project dialog.
@Composable
private fun BrowseButton(onClick: () -> Unit) {
    IdeButton("Browse…", onClick, Modifier.padding(top = 8.dp, bottom = 8.dp).testTag(AgentTags.AGENT_BASE_BROWSE))
}

// Another project is started in its own window: offered where windows can be added.
@Composable
private fun OtherProjectButton(available: Boolean, onClick: () -> Unit) {
    if (!available) return
    IdeButton("Other project…", onClick, Modifier.padding(bottom = 8.dp).testTag(AgentTags.AGENT_BASE_OTHER_PROJECT))
}

@Composable
private fun ProjectRow(path: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().resultRow(selected).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(AgentTags.agentBaseProject(path)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(folderName(path), Modifier.weight(1f))
        Label(path, color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}

// The last folder of a path of the server, which writes it with `/` or a backslash.
private fun folderName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return name.ifEmpty { path }
}

// The projects the query is in, the most recent first as the server lists them.
private fun matchingProjects(projects: List<String>, query: String): List<String> {
    val needle = query.trim()
    return if (needle.isEmpty()) projects else projects.filter { it.contains(needle, ignoreCase = true) }
}

// The status of the list keeps its line whether it says something or not: the remote branches are fetched while the
// list is shown, and a line coming and going would move the branches under the pointer, between a press and its click.
@Composable
private fun PickerStatus(agent: AgentState, picker: BasePicker) {
    val failure = picker.failure
    if (failure != null) {
        Label(failure, Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_ERROR), color = IdeColors.Error, fontSize = 12.sp)
        IdeButton("Start in the project", { agent.chooseBase(null) }, Modifier.padding(top = 8.dp).testTag(AgentTags.AGENT_BASE_PROJECT))
        return
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(STATUS_HEIGHT), contentAlignment = Alignment.CenterStart) {
        val fetchError = picker.choices?.fetchError
        when {
            fetchError != null -> {
                val text = "The remote branches could not be fetched, the known ones are listed: $fetchError"
                Label(text, Modifier.testTag(AgentTags.AGENT_BASE_FETCH_ERROR), color = IdeColors.TextSecondary, fontSize = 12.sp)
            }

            picker.choices == null -> {
                Label("Reading the branches…", color = IdeColors.TextSecondary, fontSize = 12.sp)
            }

            picker.fetching -> {
                Label("Fetching the remote branches…", Modifier.testTag(AgentTags.AGENT_BASE_FETCHING), color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
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

// The project's own branch first, then the remote branches (the default one first), then the local ones; those the query is in.
private fun matching(choices: BranchChoices, query: String): List<BaseChoice> {
    val current = choices.current
    val own = BaseChoice(null, "${current ?: "Current branch"} (the project itself)", "no worktree")
    val local = choices.local.map { BaseChoice(it, it, "local") }
    // The remote's default branch (`origin/main`) leads the remote ones, the others keep their order.
    val (defaults, others) = choices.remote.partition { it == choices.defaultRemote }
    val remote = (defaults + others).map { BaseChoice(it, it, "remote") }
    val all = listOf(own) + remote + local
    val needle = query.trim()
    return if (needle.isEmpty()) all else all.filter { it.base == null || it.label.contains(needle, ignoreCase = true) }
}
