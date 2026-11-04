package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
