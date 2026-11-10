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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
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
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) { drawBadgeShape(badge, this.size.width) }
            if (badge.letter.isNotEmpty()) BasicText(badge.letter, style = badgeStyle(badge))
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
    IdeIcon(Lucide.Check, modifier, color)
}

@Composable
fun CloseIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.X, modifier, color)
}

/** Clock with a backward arrow: the history of the branches (Branches tool window). */
@Composable
fun HistoryIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.History, modifier, color)
}

/** Push pin, filled when [pinned]: pins a branch in the toolbar. */
@Composable
fun PinIcon(pinned: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.Pin, modifier, color, filled = pinned)
}

@Composable
fun ArrowIcon(up: Boolean, modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(if (up) Lucide.ArrowUp else Lucide.ArrowDown, modifier, color)
}

@Composable
fun SearchIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.Search, modifier, color)
}

/** Two side-by-side panes: switches the diff viewer to the side-by-side layout. */
@Composable
fun SplitDiffIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.Columns2, modifier, color)
}

/** Crosshair: "select the opened file" in the project view. */
@Composable
fun LocateIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.LocateFixed, modifier, color)
}

@Composable
fun BranchIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.GitBranch, modifier, color)
}

/** Circular arrows: reloads what a panel shows. */
@Composable
fun RefreshIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.RefreshCw, modifier, color, size = 14.dp)
}

/** A triangle pointing right: runs something. */
@Composable
fun PlayIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary, filled: Boolean = false) {
    IdeIcon(Lucide.Play, modifier, color, size = 14.dp, filled = filled)
}

/** A square: stops what runs. */
@Composable
fun StopIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary, filled: Boolean = false) {
    IdeIcon(Lucide.Square, modifier, color, size = 14.dp, filled = filled)
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
            TextStyle(
                fontFamily = IdeFonts.ui,
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** Right-pointing chevron in a [side]-pixel square at the origin (rotate it to point down). */
fun DrawScope.drawChevron(side: Float, color: Color) {
    drawLucide(Lucide.ChevronRight, color, side)
}
