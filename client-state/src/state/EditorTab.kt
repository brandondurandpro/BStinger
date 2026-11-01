package org.example.kotlineditor.state

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.Job
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitLineChange

sealed interface TabStatus {
    data object Loading : TabStatus

    class Ready(val document: Document) : TabStatus

    class Unsupported(val message: String) : TabStatus

    /** A diff tab: the changes of the file since the last commit. */
    class Diff(val lines: List<DiffLine>) : TabStatus
}

/** Horizontal offset shared by every line of an editor; read in the layout phase so scrolling never recomposes. */
@Stable
class HorizontalScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    var maxOffset by mutableFloatStateOf(0f)
        private set

    val scrollableState =
        ScrollableState { delta ->
            val old = offset
            offset = (offset - delta).coerceIn(0f, maxOffset)
            old - offset
        }

    /** Updates the scrollable range (the widest line changed, or the viewport was resized). */
    fun updateRange(value: Float) {
        maxOffset = value
        offset = offset.coerceIn(0f, value)
    }

    fun scrollTo(value: Float) {
        offset = value.coerceIn(0f, maxOffset)
    }
}

/**
 * Identity of a tab showing [entry] as text, as its uncommitted diff, or as the diff of [commit]. The path is taken
 * with `/` separators: on Windows the tree lists `C:\p\a.kt` while a path rebuilt from Git (a diff, a commit) is
 * `C:\p/a.kt`, and both must open the same tab.
 */
fun tabKey(entry: FileEntry, isDiff: Boolean, commit: CommitDiffTarget?): String {
    val path = entry.path.replace('\\', '/')
    return when {
        commit != null -> "diff@${commit.hash}:$path"
        isDiff -> "diff:$path"
        else -> path
    }
}

/** The commit a diff tab shows, instead of the working tree's changes: [oldPath] is the former path of a rename. */
data class CommitDiffTarget(val hash: String, val shortHash: String, val oldPath: String? = null)

@Stable
class EditorTab(
    val entry: FileEntry,
    val relativePath: String,
    val isDiff: Boolean = false,
    /** For a diff tab, the commit whose change it shows; null for the uncommitted changes. */
    val commit: CommitDiffTarget? = null,
) {
    /** Identity of the tab: a file can be open as text, as its uncommitted diff, and as the diff of each commit. */
    val key: String get() = tabKey(entry, isDiff, commit)

    val title: String
        get() =
            when {
                commit != null -> "${entry.name} @ ${commit.shortHash}"
                isDiff -> "Diff: ${entry.name}"
                else -> entry.name
            }

    var status: TabStatus by mutableStateOf(TabStatus.Loading)
        internal set

    val document: Document? get() = (status as? TabStatus.Ready)?.document

    /** Scroll position of the unified diff layout (diff tabs). */
    val listState = LazyListState()

    /** Scroll position of the side-by-side diff layout, kept apart from the unified one. */
    val splitListState = LazyListState()
    val horizontalScroll = HorizontalScroll()

    /** Kept per tab so switching tabs restores the scroll position, like IntelliJ. */
    val verticalScroll = VerticalScroll()

    /** Gutter marks of the lines changed since HEAD (text tabs only). */
    var lineChanges by mutableStateOf<List<GitLineChange>>(emptyList())
        internal set

    /** Blame annotations of the lines, `null` until loaded; shown when [blameVisible]. */
    var blame by mutableStateOf<List<BlameLine>?>(null)

    /** Whether the gutter shows the blame annotations of this tab. */
    var blameVisible by mutableStateOf(false)

    /** Selection to apply once the content is loaded (opening a search result). */
    internal var pendingSelection: Pair<TextPos, TextPos>? = null
    internal var lastActivated = 0L
    internal var job: Job? = null
    internal var changesJob: Job? = null
    internal var blameJob: Job? = null
}

/** One editor split: its own tabs and active tab. */
@Stable
class EditorPane {
    val tabs = mutableStateListOf<EditorTab>()

    var activeTab: EditorTab? by mutableStateOf(null)
        internal set

    /** Focus target of this pane's editor (the IDE moves the focus here after navigation). */
    val focusRequester = FocusRequester()
}
