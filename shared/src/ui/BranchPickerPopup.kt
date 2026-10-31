@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.checkoutBranch

/**
 * The branch picker of the toolbar: search every local branch, pin one or several in the toolbar, and see how far
 * each branch is ahead or behind the remote-tracking branch it follows. A click checks the branch out.
 */
@Composable
internal fun BranchPickerPopup(project: ProjectState, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val cursor = remember { ListCursor() }
    val focus = remember { FocusRequester() }
    val branches = project.gitBranchInfos
    val matches = remember(branches, query) { filterBranches(branches, query) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        project.refreshBranches()
    }
    LaunchedEffect(query, branches) {
        cursor.selected = currentIndex(matches, query)
    }
    val choose = { branch: GitBranchInfo? ->
        if (branch != null) {
            onClose()
            if (!branch.current) project.checkoutBranch(branch.name)
        }
        Unit
    }
    PopupFrame(Tags.BRANCH_PICKER, onClose, width = 760) {
        Column {
            BranchPickerHeader(project, branches.size, onClose)
            IdeTextField(
                query,
                { query = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag(Tags.BRANCH_SEARCH)
                    .focusRequester(focus)
                    .popupKeys({ choose(matches.getOrNull(cursor.selected)) }, onClose) {
                        cursor.move(it, matches.size)
                    },
                placeholder = "Search branches…",
                leading = { SearchIcon(color = IdeColors.TextSecondary) },
                trailing = {
                    if (query.isNotEmpty()) {
                        Label("${matches.size}", color = IdeColors.TextSecondary, fontSize = 11.sp)
                    }
                },
            )
            BranchResults(project, branches, matches, query, cursor, choose)
            BranchPickerFooter(project.selectedBranches.size)
        }
    }
}

@Composable
private fun BranchResults(
    project: ProjectState,
    branches: List<GitBranchInfo>,
    matches: List<GitBranchInfo>,
    query: String,
    cursor: ListCursor,
    choose: (GitBranchInfo?) -> Unit,
) {
    when {
        branches.isEmpty() -> {
            PickerMessage(
                "No branch here yet, or the server is out of reach.",
                Tags.BRANCH_EMPTY,
            )
        }

        matches.isEmpty() -> {
            PickerMessage(
                "No branch matches \"$query\"",
                Tags.BRANCH_NO_MATCH,
            )
        }

        else -> {
            ResultList(matches, cursor.selected) { index, branch ->
                BranchRow(
                    project,
                    branch,
                    query,
                    selected = index == cursor.selected,
                ) { choose(branch) }
            }
        }
    }
}

@Composable
private fun BranchPickerHeader(project: ProjectState, count: Int, onClose: () -> Unit) {
    val behind = project.gitBranchInfos.count { it.behind > 0 }
    val ahead = project.gitBranchInfos.count { it.ahead > 0 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).background(IdeColors.Accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BranchIcon(color = IdeColors.Accent)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Label("Branches", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Label(
                when {
                    count == 0 -> "Loading local branches…"
                    behind == 0 && ahead == 0 -> "$count local · none ahead or behind their remote"
                    else -> "$count local · ${remoteSummary(behind, ahead)}"
                },
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        val pinned = project.selectedBranches.size
        if (pinned > 0) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .background(IdeColors.Accent.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Label("$pinned selected", color = IdeColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        IconButton({ project.refreshBranches() }, Modifier.testTag(Tags.BRANCH_REFRESH)) {
            Label("⟳", color = IdeColors.TextSecondary, fontSize = 16.sp)
        }
        IconButton(onClose) { CloseIcon() }
    }
}

@Composable
private fun PickerMessage(text: String, tag: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun BranchRow(
    project: ProjectState,
    branch: GitBranchInfo,
    query: String,
    selected: Boolean,
    onChoose: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(branchRowBackground(branch, selected, hovered), RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onChoose)
            .testTag(ItemTags.branchItem(branch.name))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchCheckbox(branch.name in project.selectedBranches, Modifier.testTag(ItemTags.branchSelect(branch.name))) {
            project.toggleBranchSelection(branch.name)
        }
        BranchIcon(
            Modifier.padding(start = 10.dp),
            color = if (branch.current) IdeColors.Accent else IdeColors.TextSecondary,
        )
        BranchName(branch, query, Modifier.weight(1f).padding(start = 8.dp))
        BranchSyncBadge(branch, modifier = Modifier.padding(start = 10.dp))
        if (!branch.current) {
            Label(
                "Checkout",
                Modifier.padding(start = 12.dp).widthIn(min = 52.dp),
                color = if (hovered || selected) IdeColors.Accent else IdeColors.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

private fun branchRowBackground(branch: GitBranchInfo, selected: Boolean, hovered: Boolean): Color =
    when {
        selected -> IdeColors.Selection
        hovered -> IdeColors.Hover
        branch.current -> IdeColors.Accent.copy(alpha = 0.07f)
        else -> Color.Transparent
    }

@Composable
private fun BranchName(branch: GitBranchInfo, query: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                highlightedBranch(branch.name, query),
                style = TextStyle(fontSize = 12.sp, color = IdeColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (branch.current) CurrentBranchPill()
        }
        Label(
            upstreamText(branch),
            Modifier.padding(top = 1.dp),
            color = if (branch.upstreamGone) IdeColors.Error else IdeColors.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun BranchCheckbox(checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(18.dp)
            .background(if (checked) IdeColors.Accent else Color.Transparent, shape)
            .border(1.dp, if (checked) IdeColors.Accent else IdeColors.InputBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) CheckIcon(Modifier.size(12.dp), color = Color.White)
    }
}

@Composable
private fun CurrentBranchPill() {
    Box(
        Modifier
            .padding(start = 8.dp)
            .background(IdeColors.Accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Label("current", color = IdeColors.Accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Distance from the upstream branch: `↑` commits to push, `↓` commits to pull (the remote is ahead). Fetches only
 * happen when the user pulls or synchronizes, so these counts are as fresh as the last fetch.
 */
@Composable
internal fun BranchSyncBadge(branch: GitBranchInfo, modifier: Modifier = Modifier, compact: Boolean = false) {
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier.testTag(ItemTags.branchSync(branch.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val status = syncStatus(branch, compact)
        if (status != null) {
            Label(status.first, color = status.second, fontSize = fontSize)
        } else {
            SyncCounts(branch, fontSize)
        }
    }
}

// Status shown instead of the ahead/behind counts; null means the branch has counts to show.
private fun syncStatus(branch: GitBranchInfo, compact: Boolean): Pair<String, Color>? =
    when {
        branch.upstreamGone -> "gone" to IdeColors.Error
        branch.upstream == null -> if (compact) null else "no remote" to IdeColors.TextSecondary
        branch.ahead == 0 && branch.behind == 0 -> if (compact) null else "in sync" to IdeColors.GitAdded
        else -> null
    }

@Composable
private fun SyncCounts(branch: GitBranchInfo, fontSize: TextUnit) {
    if (branch.ahead > 0) {
        SyncCount("↑ ${branch.ahead}", ItemTags.branchAhead(branch.name), IdeColors.GitAhead, fontSize)
    }
    if (branch.behind > 0) {
        SyncCount("↓ ${branch.behind}", ItemTags.branchBehind(branch.name), IdeColors.GitBehind, fontSize)
    }
}

@Composable
private fun SyncCount(text: String, tag: String, color: Color, fontSize: TextUnit) {
    Label(
        text,
        Modifier.testTag(tag),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BranchPickerFooter(selectedCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("↑ ahead", color = IdeColors.GitAhead, fontSize = 10.sp)
        Label("↓ behind", color = IdeColors.GitBehind, fontSize = 10.sp)
        Label("since the last fetch", color = IdeColors.TextSecondary, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Label(
            if (selectedCount == 0) {
                "Select branches to pin them in the toolbar"
            } else {
                "${plural(selectedCount, "branch", "branches")} pinned in the toolbar"
            },
            Modifier.testTag(Tags.BRANCH_SELECTION_COUNT),
            color = if (selectedCount == 0) IdeColors.TextSecondary else IdeColors.Accent,
            fontSize = 10.sp,
        )
    }
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

private fun remoteSummary(behind: Int, ahead: Int): String =
    when {
        behind > 0 && ahead > 0 -> "$behind behind and $ahead ahead of their remote"
        behind > 0 -> "$behind behind ${if (behind == 1) "its" else "their"} remote"
        else -> "$ahead ahead of ${if (ahead == 1) "its" else "their"} remote"
    }

private fun upstreamText(branch: GitBranchInfo): String =
    when {
        branch.upstreamGone -> "${branch.upstream} no longer exists on the remote"
        branch.upstream != null -> "tracks ${branch.upstream}"
        else -> "no remote tracking branch"
    }

private fun filterBranches(branches: List<GitBranchInfo>, query: String): List<GitBranchInfo> {
    val q = query.trim()
    if (q.isEmpty()) return branches
    return branches
        .filter { it.name.contains(q, ignoreCase = true) }
        .sortedWith(
            compareByDescending<GitBranchInfo> { it.name.startsWith(q, ignoreCase = true) }
                .thenBy { it.name.length }
                .thenBy { it.name },
        )
}

// The current branch is selected first, so Enter checks nothing out by accident; a search selects its best match.
private fun currentIndex(matches: List<GitBranchInfo>, query: String): Int =
    if (query.isBlank()) matches.indexOfFirst { it.current }.coerceAtLeast(0) else 0

private fun highlightedBranch(name: String, query: String): AnnotatedString {
    val q = query.trim()
    val start = if (q.isEmpty()) -1 else name.indexOf(q, ignoreCase = true)
    return buildAnnotatedString {
        append(name)
        if (start >= 0) {
            addStyle(
                SpanStyle(
                    background = IdeColors.Accent.copy(alpha = 0.28f),
                    color = IdeColors.Text,
                    fontWeight = FontWeight.SemiBold,
                ),
                start,
                start + q.length,
            )
        }
    }
}
