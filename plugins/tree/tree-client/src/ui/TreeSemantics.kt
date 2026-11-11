package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import org.example.kotlineditor.state.TreeRow
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the tree canvas draws, as semantics: an invisible node per visible row (with its chevron and its name) and per
 * sticky header, for accessibility and UI tests. Nothing here is drawn or takes pointer input. As in the editor, the
 * set of rows is not recomposed while the user scrolls: the nodes follow the content in the layout phase and catch up
 * once the scroll ends.
 */
@Composable
internal fun TreeSemantics(scene: TreeScene) {
    val scroll = scene.scroll
    val scope = rememberCoroutineScope()
    val first = rememberSettledFirstRow(scroll)
    val rows = scene.tree.rows
    val count = ceil(scroll.viewportHeightPx / scene.geometry.rowHeight).toInt() + 1
    val last = minOf(rows.size, first + count)
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                scrollToIndex { index ->
                    scope.launch { scroll.scrollToRow(index) }
                    true
                }
                scrollBy { _, y ->
                    scope.launch { scroll.scrollBy(y) }
                    true
                }
                verticalScrollAxisRange =
                    ScrollAxisRange({ scroll.offsetPx }, { scroll.maxPosition * scroll.lineHeightPx })
            },
    ) {
        for (index in first until last) {
            val row = rows[index]
            key(row.entry.path) { RowNode(scene, index, row) }
        }
        StickyNodes(scene)
    }
}

@Composable
private fun RowNode(scene: TreeScene, index: Int, row: TreeRow) {
    val g = scene.geometry
    val relativePath = scene.cache.relativePath(scene.tree, row.entry.path)
    val status = scene.gitStatus()[relativePath]
    val density = LocalDensity.current
    val name = scene.cache.name(row.entry.name)
    val nameWidth = name.size.width
    val labelWidth = with(density) { nameWidth.toDp() }
    Box(
        Modifier
            .offset { IntOffset(0, scene.rowTop(index).roundToInt()) }
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .testTag(TreeTags.treeRow(relativePath)),
    ) {
        if (row.entry.isDirectory) {
            Box(
                Modifier
                    .offset { IntOffset(g.chevronLeft(row.depth).roundToInt(), 0) }
                    .size(TREE_CHEVRON_SIZE, ROW_HEIGHT)
                    .testTag(TreeTags.treeChevron(relativePath)),
            )
        }
        val statusTag = status?.let { Modifier.testTag(TreeTags.treeGitStatus(relativePath, it.name)) } ?: Modifier
        Box(
            Modifier
                .offset { IntOffset(g.labelLeft(row.depth).roundToInt(), 0) }
                .size(labelWidth, ROW_HEIGHT)
                .semantics { text = AnnotatedString(row.entry.name) }
                .then(statusTag),
        )
    }
}

// The sticky headers are only recomposed when the set of headers changes, not while one is pushed up.
@Composable
private fun StickyNodes(scene: TreeScene) {
    val indices by remember(scene) { derivedStateOf { scene.sticky().map { it.index } } }
    for (index in indices) {
        val row = scene.tree.rows.getOrNull(index) ?: continue
        key(row.entry.path) {
            Box(
                Modifier
                    .offset { IntOffset(0, scene.sticky().firstOrNull { it.index == index }?.y ?: 0) }
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .testTag(TreeTags.treeSticky(scene.cache.relativePath(scene.tree, row.entry.path))),
            )
        }
    }
}
