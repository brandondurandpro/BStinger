package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Agent button of the toolbar: highlighted while the agent window is [open]; [onClick] opens it. */
@Composable
fun AgentButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(IdeColors.Editor, IdeShapes.Large)
            .border(1.dp, IdeColors.Separator, IdeShapes.Large)
            .padding(2.dp)
            .testTag(AgentTags.MODE_SWITCH),
    ) {
        ModeButton("Agent", open, AgentTags.MODE_AGENT, onClick)
    }
}

@Composable
internal fun ModeButton(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Label(text, color = if (selected) IdeColors.Text else IdeColors.TextSecondary, fontSize = 12.sp)
    }
}
