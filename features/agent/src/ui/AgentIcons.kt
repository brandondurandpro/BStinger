package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A speech bubble: a conversation with the agent. */
@Composable
fun SpeechBubbleIcon(color: Color = IdeColors.Text) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.4.dp.toPx()
        val bubble = Size(size.width, size.height * 0.72f)
        drawRoundRect(color, size = bubble, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        val tail =
            Path().apply {
                moveTo(size.width * 0.25f, bubble.height)
                lineTo(size.width * 0.22f, size.height)
                lineTo(size.width * 0.5f, bubble.height)
            }
        drawPath(tail, color, style = Stroke(stroke))
    }
}

/** Two sheets of paper, one behind the other: a copy to be made. */
@Composable
fun CopyIcon(color: Color = IdeColors.TextSecondary) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.2.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.38f, 0f),
            size = Size(size.width * 0.52f, size.height * 0.62f),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.3f),
            size = Size(size.width * 0.62f, size.height * 0.66f),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
    }
}

/** An arrow that turns back: the prompt goes back to the field, to be changed and sent again. */
@Composable
fun ResendIcon(color: Color = IdeColors.TextSecondary) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawArc(
            color,
            startAngle = 210f,
            sweepAngle = 250f,
            useCenter = false,
            style = Stroke(stroke),
        )
        val head =
            Path().apply {
                moveTo(size.width * 0.44f, size.height * 0.02f)
                lineTo(size.width * 0.86f, size.height * 0.1f)
                lineTo(size.width * 0.62f, size.height * 0.26f)
            }
        drawPath(head, color, style = Stroke(stroke))
    }
}
