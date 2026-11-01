package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.ProjectState

/** IDE | Agent switch of the toolbar. */
@Composable
internal fun ModeSwitch(project: ProjectState) {
    Row(
        Modifier
            .background(IdeColors.Editor, RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
            .padding(2.dp)
            .testTag(AppTags.MODE_SWITCH),
    ) {
        ModeButton("IDE", !project.agentMode, AppTags.MODE_IDE) { project.agentMode = false }
        ModeButton("Agent", project.agentMode, AppTags.MODE_AGENT) { project.agentMode = true }
    }
}

@Composable
internal fun ModeButton(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Label(text, color = if (selected) IdeColors.Text else IdeColors.TextSecondary, fontSize = 12.sp)
    }
}
