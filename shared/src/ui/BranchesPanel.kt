@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.state.BranchHistoryState
import org.example.kotlineditor.state.BranchSection
import org.example.kotlineditor.state.Pane
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.openCommitDiff
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val COMMIT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

// Rows left before the end of the loaded commits when the next page is asked for.
private const val PREFETCH_ROWS = 30
private const val MAX_REF_PILLS = 3

// Width the commit list keeps however wide the side panes were dragged, in dp.
private const val MIN_COMMITS_WIDTH = 200f

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
            ) {
                // Like the terminal: the window is only hidden, its state is kept.
                MinimizeIcon()
            }
        }
        if (!project.isGitRepository) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Label("Not a Git repository", color = IdeColors.TextSecondary)
            }
        } else {
            HistoryColumns(project, history)
        }
    }
}

// Branches | commits | details, separated by splitters. The side panes keep the width the user dragged them to
// (saved with the layout), but never leave the commits less than MIN_COMMITS_WIDTH: a narrow window shrinks them.
@Composable
private fun HistoryColumns(project: ProjectState, history: BranchHistoryState) {
    val layout = project.toolWindows
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val commit = history.selectedCommit
        val splitters = if (commit == null) 1 else 2
        val available = maxWidth.value - MIN_COMMITS_WIDTH - splitters * SPLITTER_SIZE.value
        val wantedBranches = layout.widthOf(Pane.HistoryBranches)
        val wantedDetails = if (commit == null) 0f else layout.widthOf(Pane.HistoryDetails)
        val scale = if (wantedBranches + wantedDetails > available) available / (wantedBranches + wantedDetails) else 1f
        val branchesWidth = (wantedBranches * scale).coerceAtLeast(0f)
        val detailsWidth = (wantedDetails * scale).coerceAtLeast(0f)
        Row(Modifier.fillMaxSize()) {
            BranchColumn(project, history, Modifier.width(branchesWidth.dp).fillMaxHeight())
            SplitterBox(horizontalBar = false, tag = Tags.HISTORY_BRANCHES_SPLITTER) { delta ->
                layout.resizePane(Pane.HistoryBranches, with(density) { delta.toDp().value })
            }
            CommitColumn(history, Modifier.weight(1f).fillMaxHeight())
            if (commit != null) {
                // Dragging this splitter to the left widens the details, on its right.
                SplitterBox(horizontalBar = false, tag = Tags.HISTORY_DETAILS_SPLITTER) { delta ->
                    layout.resizePane(Pane.HistoryDetails, -with(density) { delta.toDp().value })
                }
                CommitDetailsColumn(commit, history.details, Modifier.width(detailsWidth.dp).fillMaxHeight()) { file ->
                    project.openCommitDiff(commit, file)
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
        val localOpen = BranchSection.Local !in history.collapsedSections
        val remoteOpen = BranchSection.Remote !in history.collapsedSections
        LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
            item { SectionTitle(history, BranchSection.Local, shownLocals.size) }
            if (localOpen) {
                items(shownLocals, key = { "local:${it.name}" }) { branch ->
                    HistoryBranchRow(branch.name, history.selectedRef == branch.name, local = branch) {
                        history.select(branch.name)
                    }
                }
            }
            item { SectionTitle(history, BranchSection.Remote, shownRemotes.size) }
            if (remoteOpen) {
                items(shownRemotes, key = { "remote:$it" }) { name ->
                    HistoryBranchRow(name, history.selectedRef == name, local = null) { history.select(name) }
                }
            }
        }
    }
}

// Header of a section of the branch list; a click folds or unfolds it, like a tree node.
@Composable
private fun SectionTitle(history: BranchHistoryState, section: BranchSection, count: Int) {
    val open = section !in history.collapsedSections
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { history.toggleSection(section) }
            .testTag(ItemTags.historySection(section.name))
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(expanded = open, Modifier.size(14.dp))
        Label(section.title, color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
    LaunchedEffect(history.selectedRef, history.appliedQuery) { listState.scrollToItem(0) }
    LoadMoreWhenNearEnd(history, listState)
    val commits = history.commits
    val highlight = history.appliedQuery
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                history.selectedRef?.let { "History of $it" } ?: "Select a branch",
                Modifier.weight(1f).testTag(Tags.HISTORY_TITLE),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
            CommitSearchField(history, Modifier.width(280.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(commits, key = { it.hash }) { commit ->
                CommitRow(commit, highlight, history.selectedCommit?.hash == commit.hash) {
                    history.selectCommit(commit)
                }
            }
            item { HistoryFooter(history) }
        }
    }
}

// Searches the whole history of the branch (message, author, start of the hash); Escape clears the search.
@Composable
private fun CommitSearchField(history: BranchHistoryState, modifier: Modifier) {
    IdeTextField(
        history.commitQuery,
        history::search,
        modifier
            .testTag(Tags.HISTORY_SEARCH)
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                val clear = escape && history.commitQuery.isNotEmpty()
                if (clear) history.search("")
                clear
            },
        placeholder = "Search message, author, hash",
        leading = { SearchIcon(color = IdeColors.TextSecondary) },
        trailing = {
            if (history.commitQuery.isNotEmpty()) {
                Box(
                    Modifier.size(18.dp).clickable { history.search("") }.testTag(Tags.HISTORY_SEARCH_CLEAR),
                    contentAlignment = Alignment.Center,
                ) {
                    CloseIcon(Modifier.size(10.dp))
                }
            }
        },
    )
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

        history.complete && history.commits.isEmpty() && history.appliedQuery.isNotEmpty() -> {
            FooterText("No commit matches \"${history.appliedQuery}\"", Tags.HISTORY_NO_MATCH, IdeColors.TextSecondary)
        }

        history.complete && history.commits.isEmpty() && history.selectedRef != null -> {
            FooterText("No commit", Tags.HISTORY_EMPTY, IdeColors.TextSecondary)
        }

        history.complete && history.appliedQuery.isNotEmpty() -> {
            val found = plural(history.commits.size, "commit matches", "commits match")
            FooterText("$found \"${history.appliedQuery}\"", Tags.HISTORY_END, IdeColors.LineNumber)
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
private fun CommitRow(commit: GitCommit, highlight: String, selected: Boolean, onClick: () -> Unit) {
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
        HighlightedText(commit.subject, highlight, IdeColors.Text, 12.sp, Modifier.weight(1f))
        HighlightedText(commit.author, highlight, IdeColors.TextSecondary, 11.sp, Modifier.width(120.dp))
        Label(commitDate(commit.time), Modifier.width(110.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        HighlightedText(commit.shortHash, highlight, IdeColors.LineNumber, 11.sp, Modifier.width(64.dp))
    }
}

// [text] with the first occurrence of the search emphasized, so the reason a commit matched is visible.
@Composable
private fun HighlightedText(text: String, query: String, color: Color, fontSize: TextUnit, modifier: Modifier) {
    val start = if (query.isEmpty()) -1 else text.indexOf(query, ignoreCase = true)
    val annotated =
        buildAnnotatedString {
            append(text)
            if (start >= 0) {
                val style = SpanStyle(background = IdeColors.SearchMatch, color = IdeColors.Text)
                addStyle(style, start, start + query.length)
            }
        }
    BasicText(
        annotated,
        modifier,
        style = TextStyle(color = color, fontSize = fontSize),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
private fun CommitDetailsColumn(
    commit: GitCommit,
    details: GitCommitDetails?,
    modifier: Modifier,
    onOpenFile: (GitCommitFile) -> Unit,
) {
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
                    // A click opens the diff editor on what this commit changed in the file.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(file) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag(ItemTags.historyFile(file.path))
                            .padding(vertical = 2.dp),
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
