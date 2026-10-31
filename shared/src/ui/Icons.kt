@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FileIcon(name: String, isDirectory: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    if (isDirectory) {
        FolderIcon(modifier.size(size))
        return
    }
    val badge = fileBadge(name)
    if (badge == null) {
        PlainFileIcon(modifier.size(size))
    } else {
        Box(
            modifier.size(size).background(badge.color, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                badge.letter,
                style =
                badgeStyle(badge),
            )
        }
    }
}

@Composable
fun FolderIcon(modifier: Modifier) {
    Canvas(modifier) { drawFolderIcon() }
}

@Composable
fun PlainFileIcon(modifier: Modifier) {
    Canvas(modifier) { drawPlainFileIcon() }
}

@Composable
fun Chevron(expanded: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp).rotate(if (expanded) 90f else 0f)) { drawChevron(size.width, color) }
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.8.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.2f, size.height * 0.52f),
            Offset(size.width * 0.42f, size.height * 0.74f),
            strokeWidth = s,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.42f, size.height * 0.74f),
            Offset(size.width * 0.82f, size.height * 0.28f),
            strokeWidth = s,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun CloseIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val a = size.width * 0.32f
        val b = size.width * 0.68f
        val s = 1.2.dp.toPx()
        drawLine(color, Offset(a, a), Offset(b, b), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(color, Offset(b, a), Offset(a, b), strokeWidth = s, cap = StrokeCap.Round)
    }
}

/** Push pin, filled when [pinned]: pins a branch in the toolbar. */
@Composable
fun PinIcon(pinned: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.2.dp.toPx()
        val head =
            Path().apply {
                moveTo(size.width * 0.34f, size.height * 0.18f)
                lineTo(size.width * 0.66f, size.height * 0.18f)
                lineTo(size.width * 0.60f, size.height * 0.42f)
                lineTo(size.width * 0.74f, size.height * 0.58f)
                lineTo(size.width * 0.26f, size.height * 0.58f)
                lineTo(size.width * 0.40f, size.height * 0.42f)
                close()
            }
        if (pinned) drawPath(head, color) else drawPath(head, color, style = Stroke(s))
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.58f),
            Offset(size.width * 0.5f, size.height * 0.86f),
            strokeWidth = s,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ArrowIcon(up: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val s = 1.3.dp.toPx()
        val (tip, base) = if (up) h * 0.25f to h * 0.75f else h * 0.75f to h * 0.25f
        drawLine(color, Offset(w / 2, base), Offset(w / 2, tip), strokeWidth = s, cap = StrokeCap.Round)
        val wing = if (up) h * 0.45f else h * 0.55f
        drawLine(color, Offset(w * 0.3f, wing), Offset(w / 2, tip), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.7f, wing), Offset(w / 2, tip), strokeWidth = s, cap = StrokeCap.Round)
    }
}

@Composable
fun SearchIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.3.dp.toPx()
        val r = size.width * 0.26f
        val c = Offset(size.width * 0.44f, size.height * 0.44f)
        drawCircle(color, r, c, style = Stroke(s))
        drawLine(
            color,
            Offset(c.x + r * 0.75f, c.y + r * 0.75f),
            Offset(size.width * 0.82f, size.height * 0.82f),
            s,
            StrokeCap.Round,
        )
    }
}

/** Two side-by-side panes: switches the diff viewer to the side-by-side layout. */
@Composable
fun SplitDiffIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.2.dp.toPx()
        val top = size.height * 0.2f
        val bottom = size.height * 0.8f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.1f, top),
            size = Size(size.width * 0.8f, bottom - top),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(s),
        )
        drawLine(color, Offset(size.width / 2f, top), Offset(size.width / 2f, bottom), strokeWidth = s)
    }
}

/** Crosshair: "select the opened file" in the project view. */
@Composable
fun LocateIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.2.dp.toPx()
        val c = center
        val r = size.width * 0.28f
        drawCircle(color, r, c, style = Stroke(s))
        drawCircle(color, s, c)
        val inner = r * 0.55f
        val outer = size.width * 0.46f
        drawLine(color, Offset(c.x, c.y - outer), Offset(c.x, c.y - inner), s)
        drawLine(color, Offset(c.x, c.y + inner), Offset(c.x, c.y + outer), s)
        drawLine(color, Offset(c.x - outer, c.y), Offset(c.x - inner, c.y), s)
        drawLine(color, Offset(c.x + inner, c.y), Offset(c.x + outer, c.y), s)
    }
}

@Composable
fun BranchIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val s = 1.2.dp.toPx()
        val r = size.width * 0.12f
        val top = Offset(size.width * 0.32f, size.height * 0.22f)
        val bottom = Offset(size.width * 0.32f, size.height * 0.78f)
        val side = Offset(size.width * 0.70f, size.height * 0.36f)
        drawLine(color, Offset(top.x, top.y + r), Offset(bottom.x, bottom.y - r), s)
        drawCircle(color, r, top, style = Stroke(s))
        drawCircle(color, r, bottom, style = Stroke(s))
        drawCircle(color, r, side, style = Stroke(s))
        val path =
            Path().apply {
                moveTo(side.x, side.y + r)
                quadraticTo(side.x, size.height * 0.62f, bottom.x + r * 0.5f, size.height * 0.66f)
            }
        drawPath(path, color, style = Stroke(s))
    }
}

/** Colored square with the project's initials, like IntelliJ's project widget. */
@Composable
fun ProjectBadge(name: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val initials =
        name
            .split('-', '_', ' ', '.')
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
    val palette = listOf(0xFF5B8DEF, 0xFFD06B6B, 0xFF6DAF7A, 0xFFC78A4B, 0xFF9A72D6, 0xFF4BA7B5, 0xFFC4677F)
    val color = Color(palette[(name.hashCode() and Int.MAX_VALUE) % palette.size])
    Box(modifier.size(size).background(color, RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        BasicText(
            initials,
            style =
            TextStyle(color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold),
        )
    }
}
