package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val CONFIRM_MILLIS = 3_000L

/**
 * A text button for what cannot be undone (throwing changes away): the first click asks "Sure?", the second one,
 * within a few seconds, runs [onConfirm]; the question lapses by itself.
 */
@Composable
fun ConfirmButton(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
    Label(
        if (confirming) "Sure?" else label,
        modifier.clickable(enabled = enabled, onClick = click).testTag(tag).padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (confirming) IdeColors.Error else IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
}
