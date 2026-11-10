package org.example.kotlineditor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.IconShape
import org.example.kotlineditor.model.LanguageIcon
import org.example.kotlineditor.model.LanguageSupport

// File icons drawn in a DrawScope, shared by the icon composables and the canvas of the project tree.

// Proportions of the plain file icon.
private const val PAGE_LEFT = 0.2f
private const val PAGE_TOP = 0.1f
private const val TEXT_LEFT = 0.33f
private const val TEXT_TOP = 0.35f
private const val TEXT_SPACING = 0.17f

@Immutable
data class FileBadge(
    val letter: String,
    val color: Color,
    val shape: IconShape = IconShape.Square,
    val textColor: Color = Color.White,
)

/** The colored badge of a file type, `null` for the plain file icon. The languages declare theirs. */
fun fileBadge(name: String): FileBadge? = LanguageSupport.forFileName(name)?.icon?.let(::badgeOf)

private fun badgeOf(icon: LanguageIcon): FileBadge =
    FileBadge(icon.label, Color(icon.color), icon.shape, Color(icon.labelColor))

// The Vue logo (its SVG, in its own units): the green chevron and the dark one inside it.
private const val VUE_WIDTH = 261.76f
private const val VUE_HEIGHT = 226.69f
private val VUE_GREEN = Color(0xFF41B883)
private val VUE_DARK = Color(0xFF35495E)
private val VUE_OUTER =
    listOf(161.096f to 0f, 130.871f to 52.352f, 100.647f to 0f, 0f to 0f, 130.872f to 226.689f, 261.749f to 0f)
private val VUE_INNER =
    listOf(161.096f to 0f, 130.871f to 52.352f, 100.647f to 0f, 52.346f to 0f, 130.872f to 136.011f, 209.398f to 0f)

// Proportions of the shapes of a badge.
private const val SHIELD_TOP = 0.08f
private const val SHIELD_SIDE = 0.12f
private const val SHIELD_WAIST = 0.8f
private const val SHIELD_TIP = 0.95f
private const val SHIELD_TIP_SIDE = 0.2f
private const val OVAL_HEIGHT = 0.7f
private const val VUE_SCALE = 0.9f

/** The shape of [badge] filling a square of [side] pixels: the label goes over it, drawn by the caller. */
fun DrawScope.drawBadgeShape(badge: FileBadge, side: Float) {
    when (badge.shape) {
        IconShape.Square -> {
            val radius = 3.dp.toPx()
            drawRoundRect(badge.color, size = Size(side, side), cornerRadius = CornerRadius(radius, radius))
        }

        IconShape.Shield -> {
            val shield =
                Path().apply {
                    moveTo(side * SHIELD_SIDE, side * SHIELD_TOP)
                    lineTo(side * (1 - SHIELD_SIDE), side * SHIELD_TOP)
                    lineTo(side * (1 - SHIELD_TIP_SIDE), side * SHIELD_WAIST)
                    lineTo(side / 2, side * SHIELD_TIP)
                    lineTo(side * SHIELD_TIP_SIDE, side * SHIELD_WAIST)
                    close()
                }
            drawPath(shield, badge.color)
        }

        IconShape.Oval -> {
            val height = side * OVAL_HEIGHT
            drawOval(badge.color, topLeft = Offset(0f, (side - height) / 2), size = Size(side, height))
        }

        IconShape.Vue -> {
            drawVueChevron(VUE_OUTER, VUE_GREEN, side)
            drawVueChevron(VUE_INNER, VUE_DARK, side)
        }
    }
}

private fun DrawScope.drawVueChevron(points: List<Pair<Float, Float>>, color: Color, side: Float) {
    val width = side * VUE_SCALE
    val height = width * VUE_HEIGHT / VUE_WIDTH
    val left = (side - width) / 2
    val top = (side - height) / 2
    val path =
        Path().apply {
            points.forEachIndexed { index, (x, y) ->
                val point = Offset(left + x / VUE_WIDTH * width, top + y / VUE_HEIGHT * height)
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
    drawPath(path, color)
}

fun badgeStyle(badge: FileBadge) =
    TextStyle(
        color = badge.textColor,
        fontSize = if (badge.letter.length > 1) 7.sp else 9.sp,
        fontWeight = FontWeight.Bold,
    )

/** The folder icon, filling the draw scope. */
fun DrawScope.drawFolderIcon(w: Float = size.width, h: Float = size.height) {
    val stroke = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round)
    val path =
        Path().apply {
            moveTo(w * 0.08f, h * 0.22f)
            lineTo(w * 0.40f, h * 0.22f)
            lineTo(w * 0.50f, h * 0.34f)
            lineTo(w * 0.92f, h * 0.34f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.08f, h * 0.82f)
            close()
        }
    drawPath(path, IdeColors.Folder.copy(alpha = 0.25f))
    drawPath(path, IdeColors.Folder, style = stroke)
}

/** The icon of a file without a badge, filling the draw scope: a page with three lines of text. */
fun DrawScope.drawPlainFileIcon(w: Float = size.width, h: Float = size.height) {
    drawRoundRect(
        color = IdeColors.TextSecondary,
        topLeft = Offset(w * PAGE_LEFT, h * PAGE_TOP),
        size = Size(w * (1 - 2 * PAGE_LEFT), h * (1 - 2 * PAGE_TOP)),
        cornerRadius = CornerRadius(1.5.dp.toPx()),
        style = Stroke(width = 1.2.dp.toPx()),
    )
    for (i in 0..2) {
        val y = h * (TEXT_TOP + i * TEXT_SPACING)
        drawLine(IdeColors.TextSecondary, Offset(w * TEXT_LEFT, y), Offset(w * (1 - TEXT_LEFT), y), 1.dp.toPx())
    }
}
