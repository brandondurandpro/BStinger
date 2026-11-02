package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.FileTreeState
import org.example.kotlineditor.state.StickyHeader
import org.example.kotlineditor.state.TreeHost
import org.example.kotlineditor.state.TreeOutline
import org.example.kotlineditor.state.TreeRow
import org.example.kotlineditor.state.TreeScroll
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.collapseOrAscend
import org.example.kotlineditor.state.expandOrDescend
import org.example.kotlineditor.state.moveSelection
import org.example.kotlineditor.state.selectedIndex
import org.example.kotlineditor.state.stickyHeaders
import kotlin.math.floor
import kotlin.math.roundToInt

private const val DOUBLE_CLICK_MILLIS = 400L
private const val FAR_AWAY = Int.MAX_VALUE / 2

// At most a third of the visible rows are covered by sticky directory names.
private const val STICKY_SHARE = 3

// Keyboard of the project tree: arrows navigate and expand/collapse, Enter opens.
private val TREE_KEYS =
    listOf(
        binding<TreeHost>(setOf(Key.DirectionDown)) { tree.moveSelection(1) },
        binding(setOf(Key.DirectionUp)) { tree.moveSelection(-1) },
        binding(setOf(Key.DirectionRight)) { tree.expandOrDescend() },
        binding(setOf(Key.DirectionLeft)) { tree.collapseOrAscend() },
        binding(setOf(Key.MoveHome)) { tree.moveSelection(-FAR_AWAY) },
        binding(setOf(Key.MoveEnd)) { tree.moveSelection(FAR_AWAY) },
        binding(
            setOf(Key.Enter, Key.NumPadEnter),
        ) { tree.rows.getOrNull(tree.selectedIndex())?.let { openFile(it.entry) } },
    )

/** Where a press landed in the tree: a row (on its chevron or not), or a sticky header. */
private sealed interface TreeHit {
    val row: TreeRow

    class Row(override val row: TreeRow, val onChevron: Boolean) : TreeHit

    class Header(override val row: TreeRow, val header: StickyHeader) : TreeHit
}

/**
 * Lazily loaded project tree with keyboard navigation, sticky directory names and a context menu, drawn on one canvas
 * like the editor: scrolling only redraws, nothing is composed per row and each name is laid out once.
 */
@Composable
fun ProjectTree(project: TreeHost, modifier: Modifier = Modifier) {
    val tree = project.tree
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Double click detection without delaying the single click (Compose's onDoubleClick delays onClick).
    val lastClick = remember { LastClick() }
    var contextMenu by remember { mutableStateOf<Pair<FileEntry, Offset>?>(null) }
    val scene = rememberTreeScene(project) { focused }
    val scope = rememberCoroutineScope()
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    val rowCount = tree.rows.size
    // Read here, not in the side effect, so that a new size recomposes.
    val height = viewportHeight
    SideEffect { scene.scroll.updateLayout(rowCount, height, scene.geometry.rowHeight.toFloat()) }
    FollowSelection(scene)
    Box(
        modifier
            .testTag(TreeTags.TREE)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .onKeyEvent { it.type == KeyEventType.KeyDown && TREE_KEYS.dispatch(it, project) }
            .focusable()
            .focusOnAnyPress(focusRequester)
            .onSizeChanged { viewportHeight = it.height.toFloat() }
            .scrollable(scene.scroll.scrollableState, Orientation.Vertical, reverseDirection = true)
            .clipToBounds(),
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind { drawTree(scene) }
                .treePresses(scene, { hit, time -> onPress(project, scene, hit, time, lastClick, scope) }) { hit, at ->
                    tree.select(hit.row.entry)
                    contextMenu = hit.row.entry to at
                },
        )
        TreeSemantics(scene)
        contextMenu?.let { (entry, position) ->
            Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
                TreeContextMenu(project, entry, DpOffset.Zero) { contextMenu = null }
            }
        }
        VerticalScrollbar(
            remember(scene.scroll) { VerticalScrollAdapter(scene.scroll) },
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private fun onPress(
    project: TreeHost,
    scene: TreeScene,
    hit: TreeHit,
    time: Long,
    lastClick: LastClick,
    scope: CoroutineScope,
) {
    val tree = project.tree
    when (hit) {
        is TreeHit.Header -> {
            scope.launch {
                // Its ancestors stay pinned above it once it is back in view.
                scene.scroll.scrollToRow((hit.header.index - hit.row.depth).coerceAtLeast(0))
                tree.select(hit.row.entry)
            }
        }

        is TreeHit.Row -> {
            tree.select(hit.row.entry)
            if (hit.onChevron) {
                tree.toggle(hit.row.entry)
            } else if (lastClick.isDouble(hit.row.entry.path, time)) {
                project.openFile(hit.row.entry)
            }
        }
    }
}

@Composable
private fun rememberTreeScene(project: TreeHost, focused: () -> Boolean): TreeScene {
    val tree = project.tree
    val density = LocalDensity.current
    val geometry = remember(density) { TreeRowGeometry(density) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val cache = remember(measurer) { TreeTextCache(measurer) }
    val scroll = tree.scroll
    val outline = remember(tree) { derivedStateOf { TreeOutline(tree.rows) } }
    val sticky = rememberStickyHeaders(outline, scroll, geometry.rowHeight)
    return remember(project, scroll, geometry, cache, sticky) {
        TreeScene(tree, scroll, geometry, cache, { project.gitStatus }, focused) { sticky.value }
    }
}

// The sticky headers, recomputed on scroll but only republished when they change.
@Composable
private fun rememberStickyHeaders(outline: State<TreeOutline>, scroll: VerticalScroll, rowHeight: Int) =
    remember(outline, scroll, rowHeight) {
        derivedStateOf(structuralEqualityPolicy()) {
            val position = TreeScroll(scroll.firstRow, scroll.rowOffsetPx.roundToInt(), rowHeight)
            val viewport = (scroll.viewportHeightPx / rowHeight).toInt()
            stickyHeaders(outline.value, position, (viewport / STICKY_SHARE).coerceAtLeast(1))
        }
    }

// Keeps the selected row visible, out from under the sticky headers, when it is moved with the keyboard or revealed.
@Composable
private fun FollowSelection(scene: TreeScene) {
    val tree: FileTreeState = scene.tree
    val scroll = scene.scroll
    LaunchedEffect(scene) {
        snapshotFlow { tree.selectedIndex() }.collectLatest { index ->
            val rowHeight = scene.geometry.rowHeight.toFloat()
            val viewport = scroll.viewportHeightPx
            if (index >= 0 && viewport > 0f) {
                // The rows may have changed in this very frame (a directory expanded to reveal a file).
                scroll.updateLayout(tree.rows.size, viewport, rowHeight)
                val top = scene.rowTop(index)
                val hidden = top < scene.sticky().size * rowHeight || top + rowHeight > viewport
                if (hidden) scroll.scrollToRow((index - scroll.visibleRows(rowHeight) / 2).coerceAtLeast(0))
            }
        }
    }
}

// Raw press events: awaitFirstDown ignores the secondary button, which opens the context menu.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.treePresses(
    scene: TreeScene,
    onPress: (TreeHit, uptimeMillis: Long) -> Unit,
    onContextMenu: (TreeHit, Offset) -> Unit,
) = pointerInput(scene) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.type == PointerEventType.Press
            val change = event.changes.firstOrNull()?.takeIf { pressed }
            val hit = change?.let { scene.hitAt(it.position) }
            if (change != null && hit != null) {
                if (event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed) {
                    onContextMenu(hit, change.position)
                } else {
                    onPress(hit, change.uptimeMillis)
                }
            }
        }
    }
}

// The sticky headers are drawn over the rows, the root on top.
private fun TreeScene.hitAt(position: Offset): TreeHit? {
    val rows = tree.rows
    val rowHeight = geometry.rowHeight
    val header = sticky().firstOrNull { position.y >= it.y && position.y < it.y + rowHeight }
    if (header != null) return rows.getOrNull(header.index)?.let { TreeHit.Header(it, header) }
    val index = scroll.firstRow + floor((position.y + scroll.rowOffsetPx) / rowHeight).toInt()
    return rows.getOrNull(index)?.let { TreeHit.Row(it, geometry.isOnChevron(it, position.x)) }
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
