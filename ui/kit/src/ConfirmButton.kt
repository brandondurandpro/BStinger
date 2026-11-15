package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val CONFIRM_MILLIS = 3_000L

private const val CONFIRMING_ALPHA = 0.12f

private val ICON_BUTTON_SIZE = 24.dp

private val ICON_SIZE = 14.dp

/**
 * A text button for what cannot be undone (throwing changes away): the first click asks "Sure?", the second one,
 * within a few seconds, runs [onConfirm]; the question lapses by itself. With an [icon], the button is a small icon
 * button at rest ([label] is then only its name for the callers: give it a tooltip) and "Sure?" once clicked.
 */
@Composable
fun ConfirmButton(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: LucideIcon? = null,
    /** What the button says once clicked. */
    confirmText: String = "Sure?",
    onConfirm: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(CONFIRM_MILLIS)
            confirming = false
        }
    }
    val click = {
        if (confirming) onConfirm()
        confirming = !confirming
    }
    val background = if (confirming) IdeColors.Error.copy(alpha = CONFIRMING_ALPHA) else Color.Transparent
    if (icon != null && !confirming) {
        Box(
            modifier
                .size(ICON_BUTTON_SIZE)
                .background(background, IdeShapes.Medium)
                .clickable(enabled = enabled, onClick = click)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            IdeIcon(icon, size = ICON_SIZE)
        }
        return
    }
    Label(
        if (confirming) confirmText else label,
        modifier
            .background(background, IdeShapes.Small)
            .clickable(enabled = enabled, onClick = click)
            .testTag(tag)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (confirming) IdeColors.Error else IdeColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}
