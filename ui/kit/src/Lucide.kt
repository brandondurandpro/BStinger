package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Side of the box Lucide draws its icons in, and the width of its strokes in that box.
private const val LUCIDE_BOX = 24f
private const val LUCIDE_STROKE = 2f

private val LucideStroke = Stroke(width = LUCIDE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)

/**
 * An icon of Lucide, the icon set of shadcn/ui: the path data of its SVG, in a 24 × 24 box, drawn with round
 * strokes 2 units wide (scaled with the icon, as Lucide does).
 */
@Immutable
class LucideIcon(vararg val paths: String) {
    /** The paths, parsed once, the first time the icon is drawn. */
    val parsed: List<Path> by lazy { paths.map { PathParser().parsePathString(it).toPath() } }
}

/**
 * Draws [icon] in a [side]-pixel square at [topLeft], stroked in [color], filled with it when [filled] (or tinted by
 * [fillAlpha]).
 */
fun DrawScope.drawLucide(
    icon: LucideIcon,
    color: Color,
    side: Float = size.minDimension,
    topLeft: Offset = Offset.Zero,
    filled: Boolean = false,
    fillAlpha: Float = if (filled) 1f else 0f,
) {
    val factor = side / LUCIDE_BOX
    translate(topLeft.x, topLeft.y) {
        scale(factor, factor, pivot = Offset.Zero) {
            icon.parsed.forEach { path ->
                if (fillAlpha > 0f) drawPath(path, color.copy(alpha = color.alpha * fillAlpha), style = Fill)
                drawPath(path, color, style = LucideStroke)
            }
        }
    }
}

/** A Lucide icon of [size] (shadcn/ui's `size-4` by default), in [color]. */
@Composable
fun IdeIcon(
    icon: LucideIcon,
    modifier: Modifier = Modifier,
    color: Color = IdeColors.TextSecondary,
    size: Dp = 16.dp,
    filled: Boolean = false,
) {
    Canvas(modifier.size(size)) { drawLucide(icon, color, filled = filled) }
}

private const val FULL_CIRCLE = "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12Z"

private const val ROUNDED_SQUARE = "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3Z"

/** The Lucide icons the IDE uses. Generated from lucide-static 0.460.0 (ISC license, see the README). */
@Suppress("LargeClass")
object Lucide {
    val ArrowDown =
        LucideIcon(
            "M12 5v14",
            "m19 12-7 7-7-7",
        )

    val ArrowUp =
        LucideIcon(
            "m5 12 7-7 7 7",
            "M12 19V5",
        )

    val ArrowUpDown =
        LucideIcon(
            "m21 16-4 4-4-4",
            "M17 20V4",
            "m3 8 4-4 4 4",
            "M7 4v16",
        )

    val Ban =
        LucideIcon(
            FULL_CIRCLE,
            "m4.9 4.9 14.2 14.2",
        )

    val Braces =
        LucideIcon(
            "M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1",
            "M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1",
        )

    val Check =
        LucideIcon(
            "M20 6 9 17l-5-5",
        )

    val ChevronRight =
        LucideIcon(
            "m9 18 6-6-6-6",
        )

    val Circle =
        LucideIcon(
            FULL_CIRCLE,
        )

    val CircleCheck =
        LucideIcon(
            FULL_CIRCLE,
            "m9 12 2 2 4-4",
        )

    val Columns2 =
        LucideIcon(
            ROUNDED_SQUARE,
            "M12 3v18",
        )

    val Ellipsis =
        LucideIcon(
            "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12Z",
            "M18 12A1 1 0 1 0 20 12A1 1 0 1 0 18 12Z",
            "M4 12A1 1 0 1 0 6 12A1 1 0 1 0 4 12Z",
        )

    val File =
        LucideIcon(
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
            "M14 2v4a2 2 0 0 0 2 2h4",
        )

    val FileText =
        LucideIcon(
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
            "M14 2v4a2 2 0 0 0 2 2h4",
            "M10 9H8",
            "M16 13H8",
            "M16 17H8",
        )

    val Folder =
        LucideIcon(
            "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 " +
                "2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
        )

    val GitBranch =
        LucideIcon(
            "M6 3L6 15",
            "M15 6A3 3 0 1 0 21 6A3 3 0 1 0 15 6Z",
            "M3 18A3 3 0 1 0 9 18A3 3 0 1 0 3 18Z",
            "M18 9a9 9 0 0 1-9 9",
        )

    val Heart =
        LucideIcon(
            "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 " +
                "2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z",
        )

    val History =
        LucideIcon(
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
            "M12 7v5l4 2",
        )

    val Info =
        LucideIcon(
            FULL_CIRCLE,
            "M12 16v-4",
            "M12 8h.01",
        )

    val LocateFixed =
        LucideIcon(
            "M2 12L5 12",
            "M19 12L22 12",
            "M12 2L12 5",
            "M12 19L12 22",
            "M5 12A7 7 0 1 0 19 12A7 7 0 1 0 5 12Z",
            "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z",
        )

    val ListFilter =
        LucideIcon(
            "M3 6h18",
            "M7 12h10",
            "M10 18h4",
        )

    val MessageSquare =
        LucideIcon(
            "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z",
        )

    val Minus =
        LucideIcon(
            "M5 12h14",
        )

    val Pencil =
        LucideIcon(
            "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z",
            "m15 5 4 4",
        )

    val Pin =
        LucideIcon(
            "M12 17v5",
            "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 " +
                "1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 " +
                "0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
        )

    val Play =
        LucideIcon(
            "M6 3L20 12L6 21L6 3Z",
        )

    val Plus =
        LucideIcon(
            "M5 12h14",
            "M12 5v14",
        )

    val RefreshCw =
        LucideIcon(
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        )

    val Search =
        LucideIcon(
            "M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11Z",
            "m21 21-4.3-4.3",
        )

    val Server =
        LucideIcon(
            "M4 2H20A2 2 0 0 1 22 4V8A2 2 0 0 1 20 10H4A2 2 0 0 1 2 8V4A2 2 0 0 1 4 2Z",
            "M4 14H20A2 2 0 0 1 22 16V20A2 2 0 0 1 20 22H4A2 2 0 0 1 2 20V16A2 2 0 0 1 4 14Z",
            "M6 6L6.01 6",
            "M6 18L6.01 18",
        )

    val Settings =
        LucideIcon(
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 " +
                "0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 " +
                "1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 " +
                "0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 " +
                "0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 " +
                "0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 " +
                "0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z",
        )

    val Square =
        LucideIcon(
            ROUNDED_SQUARE,
        )

    val SquareTerminal =
        LucideIcon(
            "m7 11 2-2-2-2",
            "M11 13h4",
            ROUNDED_SQUARE,
        )

    val Trash2 =
        LucideIcon(
            "M3 6h18",
            "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
            "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
            "M10 11L10 17",
            "M14 11L14 17",
        )

    val X =
        LucideIcon(
            "M18 6 6 18",
            "m6 6 12 12",
        )
}
