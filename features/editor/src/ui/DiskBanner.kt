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
import org.example.kotlineditor.state.Document

/**
 * Above an editor whose file the disk and the IDE disagree on: another program changed it while it had unsaved edits
 * (the user chooses which to keep, like IntelliJ's file cache conflict), or its last save failed (and why). The
 * conflict also offers Compare, a diff of the editor's content against what the disk holds, before choosing.
 */
@Composable
fun DiskBanner(document: Document, onReload: () -> Unit, onKeep: () -> Unit, onCompare: () -> Unit) {
    val error = document.saveError
    when {
        document.diskConflict -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(IdeColors.Panel)
                    .padding(horizontal = 8.dp)
                    .testTag(EditorTags.DISK_CONFLICT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("Another program changed this file, which has unsaved changes", color = IdeColors.ProblemWarning)
                Box(Modifier.weight(1f))
                IdeButton("Compare", onCompare, Modifier.testTag(EditorTags.DISK_COMPARE))
                IdeButton("Load from Disk", onReload, Modifier.padding(start = 6.dp).testTag(EditorTags.DISK_RELOAD))
                IdeButton("Keep My Changes", onKeep, Modifier.padding(start = 6.dp).testTag(EditorTags.DISK_KEEP))
            }
        }

        error != null -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(IdeColors.Panel)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("Cannot save: $error", Modifier.testTag(EditorTags.SAVE_ERROR), color = IdeColors.Error)
            }
        }
    }
}
