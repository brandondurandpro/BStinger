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

// File icons drawn in a DrawScope, shared by the icon composables and the canvas of the project tree.

// Proportions of the plain file icon.
private const val PAGE_LEFT = 0.2f
private const val PAGE_TOP = 0.1f
private const val TEXT_LEFT = 0.33f
private const val TEXT_TOP = 0.35f
private const val TEXT_SPACING = 0.17f

@Immutable
internal data class FileBadge(val letter: String, val color: Color)

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

/** The colored letter badge of a file type, `null` for the plain file icon. */
internal fun fileBadge(name: String): FileBadge? = badges[name.substringAfterLast('.', "").lowercase()]

internal fun badgeStyle(badge: FileBadge) =
    TextStyle(
        color = Color.White,
        fontSize = if (badge.letter.length > 1) 7.sp else 9.sp,
        fontWeight = FontWeight.Bold,
    )

/** The folder icon, filling the draw scope. */
internal fun DrawScope.drawFolderIcon(w: Float = size.width, h: Float = size.height) {
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
internal fun DrawScope.drawPlainFileIcon(w: Float = size.width, h: Float = size.height) {
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
