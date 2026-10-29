package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.flow.collectLatest
import org.example.kotlineditor.state.FileTreeState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.collapseOrAscend
import org.example.kotlineditor.state.expandOrDescend
import org.example.kotlineditor.state.moveSelection
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.state.selectedIndex

private const val DOUBLE_CLICK_MILLIS = 400L
private const val FAR_AWAY = Int.MAX_VALUE / 2

// Keyboard of the project tree: arrows navigate and expand/collapse, Enter opens.
private val TREE_KEYS =
    listOf(
        binding<ProjectState>(setOf(Key.DirectionDown)) { tree.moveSelection(1) },
        binding(setOf(Key.DirectionUp)) { tree.moveSelection(-1) },
        binding(setOf(Key.DirectionRight)) { tree.expandOrDescend() },
        binding(setOf(Key.DirectionLeft)) { tree.collapseOrAscend() },
        binding(setOf(Key.MoveHome)) { tree.moveSelection(-FAR_AWAY) },
        binding(setOf(Key.MoveEnd)) { tree.moveSelection(FAR_AWAY) },
        binding(
            setOf(Key.Enter, Key.NumPadEnter),
        ) { tree.rows.getOrNull(tree.selectedIndex())?.let { openFile(it.entry) } },
    )

/** Lazily loaded, virtualized project tree with keyboard navigation and a context menu. */
@Composable
fun ProjectTree(project: ProjectState, modifier: Modifier = Modifier) {
    val tree = project.tree
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Double click detection without delaying the single click (Compose's onDoubleClick delays onClick).
    val lastClick = remember { LastClick() }
    var contextMenu by remember { mutableStateOf<Pair<String, DpOffset>?>(null) }
    val density = LocalDensity.current
    FollowSelection(tree, listState)
    Box(
        modifier
            .testTag(Tags.TREE)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .onKeyEvent { it.type == KeyEventType.KeyDown && TREE_KEYS.dispatch(it, project) }
            .focusable()
            .focusOnAnyPress(focusRequester),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(tree.rows, key = { it.entry.path }, contentType = { 0 }) { row ->
                val relativePath = tree.relativePath(row.entry)
                val actions =
                    RowActions(
                        onPress = { onChevron, time ->
                            focusRequester.requestFocus()
                            tree.select(row.entry)
                            if (onChevron) {
                                tree.toggle(row.entry)
                            } else if (lastClick.isDouble(row.entry.path, time)) {
                                project.openFile(row.entry)
                            }
                        },
                        onContextMenu = { position ->
                            focusRequester.requestFocus()
                            tree.select(row.entry)
                            contextMenu = row.entry.path to
                                with(density) { DpOffset(position.x.toDp(), position.y.toDp() - ROW_HEIGHT) }
                        },
                    )
                TreeRowView(
                    row,
                    relativePath,
                    project.gitStatus[relativePath],
                    row.entry.path == tree.selectedPath && focused,
                    row.entry.path == tree.selectedPath,
                    actions,
                ) {
                    contextMenu?.takeIf { it.first == row.entry.path }?.let { (_, offset) ->
                        TreeContextMenu(project, row.entry, offset) { contextMenu = null }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

// Keeps the selected row visible when it is moved with the keyboard or revealed from the editor.
@Composable
private fun FollowSelection(tree: FileTreeState, listState: LazyListState) {
    LaunchedEffect(tree) {
        snapshotFlow { tree.selectedIndex() }.collectLatest { index ->
            val visible = listState.layoutInfo.visibleItemsInfo
            val outside = visible.isNotEmpty() && (index <= visible.first().index || index >= visible.last().index)
            if (index >= 0 && outside) listState.scrollToItem((index - visible.size / 2).coerceAtLeast(0))
        }
    }
}

// Clicking anywhere in the panel, even below the last row, focuses the tree.
private fun Modifier.focusOnAnyPress(focusRequester: FocusRequester) =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            focusRequester.requestFocus()
        }
    }

private class LastClick {
    private var path: String? = null
    private var time = 0L

    fun isDouble(path: String, now: Long): Boolean {
        val double = path == this.path && now - time < DOUBLE_CLICK_MILLIS
        this.path = if (double) null else path
        time = now
        return double
    }
}
