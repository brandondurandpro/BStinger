package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Looks like shadcn/ui's command input and opens Go to File, which is what Ctrl+P opens too.
@Composable
internal fun SearchField(onClick: () -> Unit) {
    Row(
        Modifier
            .width(SEARCH_WIDTH)
            .height(28.dp)
            .background(IdeColors.Input, IdeShapes.Medium)
            .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
            .clip(IdeShapes.Medium)
            .clickable(onClick = onClick)
            .testTag(AppTags.TOOLBAR_SEARCH)
            .padding(start = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(color = IdeColors.TextSecondary)
        Label("Search…", Modifier.weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp)
        Badge("Ctrl P", color = IdeColors.TextSecondary)
    }
}

private val SEARCH_WIDTH = 300.dp
