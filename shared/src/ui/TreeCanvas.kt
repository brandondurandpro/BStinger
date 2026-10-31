package org.example.kotlineditor.ui

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.FileTreeState
import org.example.kotlineditor.state.StickyHeader
import org.example.kotlineditor.state.TreeRow
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.relativePath
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val ROW_HEIGHT = 24.dp
internal val TREE_INDENT = 18.dp
internal val TREE_CHEVRON_SIZE = 16.dp
internal val TREE_ROW_START = 8.dp
private val ICON_SIZE = 16.dp
private val ICON_START = 2.dp
private val LABEL_START = 6.dp
private val BADGE_RADIUS = 3.dp
private const val EXPANDED_DEGREES = 90f
private const val NAME_CAPACITY = 1024
private const val PATH_CAPACITY = 1024
private const val LOADING = "  loading…"

/** Horizontal positions of the parts of a tree row, in pixels. */
internal class TreeRowGeometry(density: Density) {
    val rowHeight: Int = with(density) { ROW_HEIGHT.roundToPx() }
    private val start = with(density) { TREE_ROW_START.toPx() }
    private val indent = with(density) { TREE_INDENT.toPx() }
    val chevron = with(density) { TREE_CHEVRON_SIZE.toPx() }
    val icon = with(density) { ICON_SIZE.toPx() }
    private val iconStart = with(density) { ICON_START.toPx() }
    private val labelStart = with(density) { LABEL_START.toPx() }

    fun chevronLeft(depth: Int) = start + indent * depth

    fun iconLeft(depth: Int) = chevronLeft(depth) + chevron + iconStart

    fun labelLeft(depth: Int) = iconLeft(depth) + icon + labelStart

    fun isOnChevron(row: TreeRow, x: Float): Boolean {
        val left = chevronLeft(row.depth)
        return row.entry.isDirectory && x in left..left + chevron
    }
}

/**
 * Laid out names of the tree rows, kept by text: scrolling back over rows already seen lays nothing out again. The
 * names are laid out without color, each row draws them in its Git status color.
 */
internal class TreeTextCache(private val measurer: TextMeasurer) {
    private val style = TextStyle(fontSize = 13.sp)
    private val names = LruMap<String, TextLayoutResult>(NAME_CAPACITY)
    private val badges = HashMap<FileBadge, TextLayoutResult>()

    // Project-relative paths of the drawn rows, the keys of the Git statuses.
    private val paths = LruMap<String, String>(PATH_CAPACITY)

    fun name(text: String): TextLayoutResult =
        names.getOrPut(text) { measurer.measure(text, style, softWrap = false, maxLines = 1) }

    fun badge(badge: FileBadge): TextLayoutResult =
        badges.getOrPut(badge) { measurer.measure(badge.letter, badgeStyle(badge), softWrap = false, maxLines = 1) }

    fun relativePath(tree: FileTreeState, path: String): String = paths.getOrPut(path) { tree.relativePath(path) }
}

/** What the tree canvas draws, read in the draw phase so that scrolling and selecting only redraw. */
@Stable
internal class TreeScene(
    val tree: FileTreeState,
    val scroll: VerticalScroll,
    val geometry: TreeRowGeometry,
    val cache: TreeTextCache,
    val gitStatus: () -> Map<String, GitFileStatus>,
    val focused: () -> Boolean,
    val sticky: () -> List<StickyHeader>,
)

/** Pixels from the top of the viewport to the top of [row]. */
internal fun TreeScene.rowTop(row: Int): Float = (row - scroll.firstRow) * geometry.rowHeight - scroll.rowOffsetPx

internal fun DrawScope.drawTree(scene: TreeScene) {
    val rows = scene.tree.rows
    val first = scene.scroll.firstRow
    val last = minOf(rows.size, first + ceil(size.height / scene.geometry.rowHeight).toInt() + 1)
    val selected = scene.tree.selectedPath
    val git = scene.gitStatus()
    for (index in first until last) {
        val row = rows[index]
        val background =
            when {
                row.entry.path != selected -> Color.Transparent
                scene.focused() -> IdeColors.Selection
                else -> IdeColors.SelectionInactive
            }
        val status = git[scene.cache.relativePath(scene.tree, row.entry.path)]
        translate(top = scene.rowTop(index).roundToInt().toFloat()) {
            drawTreeRow(scene, row, background, status?.let(::gitColor) ?: IdeColors.Text, row.loading)
        }
    }
    drawStickyHeaders(scene, rows)
}

// Drawn deepest first, so that a header pushed up by the end of its directory slides under its parent.
private fun DrawScope.drawStickyHeaders(scene: TreeScene, rows: List<TreeRow>) {
    val headers = scene.sticky()
    for (header in headers.asReversed()) {
        val row = rows.getOrNull(header.index) ?: continue
        translate(top = header.y.toFloat()) {
            drawTreeRow(scene, row, IdeColors.Panel, IdeColors.Text, loading = false)
            if (header == headers.last()) {
                val y = scene.geometry.rowHeight - 1f
                drawLine(IdeColors.Separator, Offset(0f, y), Offset(size.width, y))
            }
        }
    }
}

private fun DrawScope.drawTreeRow(scene: TreeScene, row: TreeRow, background: Color, color: Color, loading: Boolean) {
    val g = scene.geometry
    val height = g.rowHeight.toFloat()
    if (background != Color.Transparent) drawRect(background, size = Size(size.width, height))
    val iconTop = ((height - g.icon) / 2).roundToInt().toFloat()
    if (row.entry.isDirectory) {
        translate(g.chevronLeft(row.depth), iconTop) {
            rotate(if (row.expanded) EXPANDED_DEGREES else 0f, Offset(g.chevron / 2, g.chevron / 2)) {
                drawChevron(g.chevron, IdeColors.TextSecondary)
            }
        }
    }
    translate(g.iconLeft(row.depth), iconTop) { drawFileIcon(scene.cache, row, g.icon) }
    val name = scene.cache.name(row.entry.name)
    val labelLeft = g.labelLeft(row.depth)
    val textTop = ((height - name.size.height) / 2).roundToInt().toFloat()
    clipRect(right = size.width - TREE_ROW_START.toPx(), bottom = height) {
        drawText(name, color, Offset(labelLeft.roundToInt().toFloat(), textTop))
        if (loading) {
            val label = scene.cache.name(LOADING)
            val left = (labelLeft + name.size.width).roundToInt().toFloat()
            drawText(label, IdeColors.TextSecondary, Offset(left, textTop))
        }
    }
}

private fun DrawScope.drawFileIcon(cache: TreeTextCache, row: TreeRow, side: Float) {
    val badge = if (row.entry.isDirectory) null else fileBadge(row.entry.name)
    when {
        row.entry.isDirectory -> {
            drawFolderIcon(side, side)
        }

        badge == null -> {
            drawPlainFileIcon(side, side)
        }

        else -> {
            val radius = BADGE_RADIUS.toPx()
            drawRoundRect(badge.color, size = Size(side, side), cornerRadius = CornerRadius(radius, radius))
            val letter = cache.badge(badge)
            drawText(letter, topLeft = Offset((side - letter.size.width) / 2, (side - letter.size.height) / 2))
        }
    }
}
