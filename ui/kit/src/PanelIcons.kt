package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

// Icons of the buttons in the panels' headers.

/** A plus, for the buttons that add something (a terminal tab). */
@Composable
fun PlusIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val a = size.width * 0.18f
        val b = size.width * 0.82f
        val middle = size.width / 2
        val s = 1.6.dp.toPx()
        drawLine(color, Offset(a, middle), Offset(b, middle), strokeWidth = s, cap = StrokeCap.Round)
        drawLine(color, Offset(middle, a), Offset(middle, b), strokeWidth = s, cap = StrokeCap.Round)
    }
}

/** A bar, for the buttons that minimize (hide) a panel. */
@Composable
fun MinimizeIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        val y = size.height / 2
        val a = size.width * 0.25f
        val b = size.width * 0.75f
        drawLine(color, Offset(a, y), Offset(b, y), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
}
