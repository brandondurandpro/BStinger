@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.state.BranchHistoryState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val COMMIT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

// Rows left before the end of the loaded commits when the next page is asked for.
private const val PREFETCH_ROWS = 30
private const val MAX_REF_PILLS = 3

/**
 * Branches tool window, like IntelliJ's Git log: the local and remote branches on the left, the history of the
 * selected branch in the middle (loaded page by page while scrolling), the selected commit's message and files on
 * the right.
 */
@Composable
internal fun BranchesPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val history = project.history
    // The first time, the history opens on the current branch, as soon as it is known.
    LaunchedEffect(project.gitBranch) { history.selectIfNone(project.gitBranch) }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.BRANCHES_PANEL)) {
        ToolWindowHeader("Branches", ToolWindow.Branches, project, drag) {
            IconButton(
                {
                    project.refreshBranches()
                    history.reload()
                },
                Modifier.testTag(Tags.HISTORY_REFRESH),
            ) {
                Label("⟳", color = IdeColors.TextSecondary)
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Branches, false) },
                Modifier.testTag(Tags.HISTORY_HIDE),
            ) { CloseIcon() }
        }
        if (!project.isGitRepository) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Label("Not a Git repository", color = IdeColors.TextSecondary)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                BranchColumn(project, history, Modifier.width(230.dp).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
                CommitColumn(history, Modifier.weight(1f).fillMaxHeight())
                history.selectedCommit?.let { commit ->
                    VerticalDivider(Modifier.fillMaxHeight())
                    CommitDetailsColumn(commit, history.details, Modifier.width(320.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun BranchColumn(project: ProjectState, history: BranchHistoryState, modifier: Modifier) {
    val query = history.branchQuery.trim()
    val locals = project.gitBranchInfos
    val remotes = history.remoteBranches
    val shownLocals = remember(locals, query) { locals.filter { it.name.contains(query, ignoreCase = true) } }
    val shownRemotes = remember(remotes, query) { remotes.filter { it.contains(query, ignoreCase = true) } }
    Column(modifier.padding(6.dp)) {
        IdeTextField(
            history.branchQuery,
            { history.branchQuery = it },
            Modifier.fillMaxWidth().testTag(Tags.HISTORY_BRANCH_SEARCH),
            placeholder = "Filter branches",
            leading = { SearchIcon(color = IdeColors.TextSecondary) },
        )
        LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
            item { SectionTitle("Local", shownLocals.size) }
            items(shownLocals, key = { "local:${it.name}" }) { branch ->
                HistoryBranchRow(branch.name, history.selectedRef == branch.name, local = branch) {
                    history.select(branch.name)
                }
            }
            item { SectionTitle("Remote", shownRemotes.size) }
            items(shownRemotes, key = { "remote:$it" }) { name ->
                HistoryBranchRow(name, history.selectedRef == name, local = null) { history.select(name) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 2.dp)) {
        Label(title, color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Label("  $count", color = IdeColors.LineNumber, fontSize = 11.sp)
    }
}

@Composable
private fun HistoryBranchRow(name: String, selected: Boolean, local: GitBranchInfo?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .testTag(ItemTags.historyBranch(name))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchIcon(color = if (local?.current == true) IdeColors.Accent else IdeColors.TextSecondary)
        Label(name, Modifier.weight(1f).padding(start = 6.dp), fontSize = 12.sp)
        local?.let { BranchSyncBadge(it, compact = true, modifier = Modifier.padding(start = 4.dp)) }
    }
}

@Composable
private fun CommitColumn(history: BranchHistoryState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.selectedRef) { listState.scrollToItem(0) }
    LoadMoreWhenNearEnd(history, listState)
    val commits = history.commits
    Column(modifier) {
        Label(
            history.selectedRef?.let { "History of $it" } ?: "Select a branch",
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).testTag(Tags.HISTORY_TITLE),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(commits, key = { it.hash }) { commit ->
                CommitRow(commit, history.selectedCommit?.hash == commit.hash) { history.selectCommit(commit) }
            }
            item { HistoryFooter(history) }
        }
    }
}

// Asks for the next page when the last loaded rows come into view. Keyed by the number of loaded commits: still near
// the end once a page arrives (a short page, a tall window) asks for the next one too.
@Composable
private fun LoadMoreWhenNearEnd(history: BranchHistoryState, listState: LazyListState) {
    LaunchedEffect(history, listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val last = if (visible.isEmpty()) 0 else visible.last().index
            val loaded = history.commits.size
            if (loaded > 0 && last >= loaded - PREFETCH_ROWS) loaded else -1
        }.collect { loaded -> if (loaded > 0) history.loadMore() }
    }
}

@Composable
private fun HistoryFooter(history: BranchHistoryState) {
    val error = history.error
    when {
        error != null -> {
            FooterText(error, Tags.HISTORY_ERROR, IdeColors.Error)
        }

        history.loading -> {
            FooterText("Loading commits…", Tags.HISTORY_LOADING, IdeColors.TextSecondary)
        }

        history.complete && history.commits.isEmpty() && history.selectedRef != null -> {
            FooterText("No commit", Tags.HISTORY_EMPTY, IdeColors.TextSecondary)
        }

        history.complete -> {
            FooterText(plural(history.commits.size, "commit", "commits"), Tags.HISTORY_END, IdeColors.LineNumber)
        }
    }
}

@Composable
private fun FooterText(text: String, tag: String, color: Color) {
    Label(text, Modifier.padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag), color = color, fontSize = 11.sp)
}

@Composable
private fun CommitRow(commit: GitCommit, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .background(if (selected) IdeColors.Selection else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag(ItemTags.historyCommit(commit.hash))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        commit.refs.take(MAX_REF_PILLS).forEach { RefPill(it) }
        if (commit.refs.size > MAX_REF_PILLS) {
            Label("+${commit.refs.size - MAX_REF_PILLS}", color = IdeColors.TextSecondary, fontSize = 10.sp)
        }
        Label(commit.subject, Modifier.weight(1f), fontSize = 12.sp)
        Label(commit.author, Modifier.width(120.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        Label(commitDate(commit.time), Modifier.width(110.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        Label(commit.shortHash, Modifier.width(64.dp), color = IdeColors.LineNumber, fontSize = 11.sp)
    }
}

@Composable
private fun RefPill(ref: String) {
    val color = if (ref.startsWith("tag: ")) IdeColors.GitBehind else IdeColors.Accent
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Label(ref.removePrefix("tag: "), Modifier.widthIn(max = 140.dp), color = color, fontSize = 10.sp)
    }
}

@Composable
private fun CommitDetailsColumn(commit: GitCommit, details: GitCommitDetails?, modifier: Modifier) {
    Column(modifier.padding(10.dp).testTag(Tags.HISTORY_DETAILS)) {
        val message = details?.message ?: commit.subject
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            message.lines().forEach { line ->
                Label(line.ifEmpty { " " }, fontSize = 12.sp)
            }
        }
        Label(
            "${commit.shortHash} · ${commit.author} · ${commitDate(commit.time)}",
            Modifier.padding(top = 6.dp),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
        )
        if (details == null) {
            Label("Loading files…", Modifier.padding(top = 8.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        } else {
            Label(
                plural(details.files.size, "file changed", "files changed"),
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(details.files, key = { it.path }) { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(ItemTags.historyFile(file.path)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileIcon(file.path.substringAfterLast('/'), isDirectory = false)
                        Label(
                            file.oldPath?.let { "$it → ${file.path}" } ?: file.path,
                            Modifier.padding(start = 6.dp),
                            color = gitColor(file.status),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun commitDate(seconds: Long): String = COMMIT_DATE.format(Instant.ofEpochSecond(seconds))
