package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A box to check with its [label] and, under it, a [description] when there is one; a click calls [onToggle]. */
@Composable
internal fun CheckRow(label: String, checked: Boolean, tag: String, description: String? = null, onToggle: () -> Unit) {
    val shape = IdeShapes.Small
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(tag)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (checked) IdeColors.Accent else Color.Transparent, shape)
                .border(1.dp, if (checked) IdeColors.Accent else IdeColors.InputBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) CheckIcon(Modifier.size(9.dp), color = IdeColors.OnAccent)
        }
        Column(Modifier.padding(start = 8.dp)) {
            Label(label)
            if (!description.isNullOrBlank()) {
                Label(description, color = IdeColors.TextSecondary, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}
