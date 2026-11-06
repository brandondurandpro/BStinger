package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The strip of labels above the rows of a compare: it names the two contents being compared, since two contents
// (what the disk holds, and what the editor holds) are showing, not one file's versions over a commit. The removed
// rows and the left numbers are the disk's, the added ones and the right numbers the editor's.
@Composable
internal fun SideLabels() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(IdeColors.Panel)
            .padding(horizontal = 12.dp)
            .testTag(EditorTags.DIFF_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("Disk", color = IdeColors.TextSecondary, fontSize = 11.sp)
        Box(Modifier.weight(1f))
        Label("Editor", color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}
