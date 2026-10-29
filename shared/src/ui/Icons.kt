package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
private data class FileBadge(val letter: String, val color: Color)

private val badges =
    mapOf(
        "kt" to FileBadge("K", Color(0xFF8F6CF5)),
        "kts" to FileBadge("K", Color(0xFF8F6CF5)),
        "java" to FileBadge("J", Color(0xFFE08855)),
        "js" to FileBadge("JS", Color(0xFFD9B43A)),
        "ts" to FileBadge("TS", Color(0xFF3C82DC)),
        "py" to FileBadge("Py", Color(0xFF3C82DC)),
        "md" to FileBadge("M", Color(0xFF5E9FD5)),
        "json" to FileBadge("{}", Color(0xFFD9B43A)),
        "xml" to FileBadge("<>", Color(0xFFE08855)),
        "html" to FileBadge("<>", Color(0xFFE08855)),
        "yaml" to FileBadge("Y", Color(0xFFDB5C5C)),
        "yml" to FileBadge("Y", Color(0xFFDB5C5C)),
        "toml" to FileBadge("T", Color(0xFF9AA7B0)),
        "gradle" to FileBadge("G", Color(0xFF5FB2A7)),
        "sh" to FileBadge(">", Color(0xFF6AAB73)),
        "bat" to FileBadge(">", Color(0xFF6AAB73)),
    )

@Composable
fun FileIcon(name: String, isDirectory: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    if (isDirectory) {
        FolderIcon(modifier.size(size))
        return
    }
    val badge = badges[name.substringAfterLast('.', "").lowercase()]
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
                TextStyle(
                    color = Color.White,
                    fontSize = if (badge.letter.length > 1) 7.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
fun FolderIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
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
}

@Composable
fun PlainFileIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = IdeColors.TextSecondary,
            topLeft = Offset(w * 0.2f, h * 0.1f),
            size = Size(w * 0.6f, h * 0.8f),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        for (i in 0..2) {
            val y = h * (0.35f + i * 0.17f)
            drawLine(IdeColors.TextSecondary, Offset(w * 0.33f, y), Offset(w * 0.67f, y), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
fun Chevron(expanded: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp).rotate(if (expanded) 90f else 0f)) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                moveTo(w * 0.40f, h * 0.28f)
                lineTo(w * 0.62f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.72f)
            }
        drawPath(path, color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
