package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.state.ScratchpadState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.scratchpadState

private const val WINDOW_NAME = "Scratchpad"
private const val NOTES_LINE = 13

/** The window this plugin contributes, named by [scratchpadToolWindow]'s defaults so the layout saves its place. */
internal val SCRATCHPAD_WINDOW = ToolWindow(WINDOW_NAME)

/** The client half of the bundled Scratchpad feature: its tool window. */
class ScratchpadClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(scratchpadToolWindow())
    }
}

/** The Scratchpad tool window: the notes of the project, loaded from the file when it is shown. */
fun scratchpadToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(SCRATCHPAD_WINDOW, Dock.Left),
        tag = ScratchpadTags.TOOL,
        group = StripGroup.Last,
        onShown = ::loadWhenShown,
        icon = Lucide.FileText,
        content = { host, drag, modifier -> ScratchpadPanel(host, drag, modifier) },
    )

// Shown again: the file may have changed meanwhile (another window, another program). The state is created on the
// first use of the window.
private fun loadWhenShown(host: ProjectHost) {
    val scratchpad = host.scratchpadState()
    host.scope.launch { scratchpad.load() }
}

/** The Scratchpad tool window: the notes of the project, an editable field, and the Save that writes the file. */
@Composable
fun ScratchpadPanel(project: ProjectHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val scratchpad = project.scratchpadState()
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(ScratchpadTags.PANEL)) {
        ToolWindowHeader(WINDOW_NAME, SCRATCHPAD_WINDOW, project.toolWindows, drag) {
            IconButton(
                { project.toolWindows.setVisible(SCRATCHPAD_WINDOW, false) },
                Modifier.testTag(ScratchpadTags.HIDE),
            ) {
                // Like the Todo window: the window is only hidden, its state is kept.
                MinimizeIcon()
            }
        }
        ScratchpadActions(project, scratchpad)
        ScratchpadBody(scratchpad, Modifier.fillMaxSize())
    }
}

@Composable
private fun ScratchpadActions(project: ProjectHost, scratchpad: ScratchpadState, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (scratchpad.unsaved) {
            Label(
                "Unsaved changes",
                Modifier.weight(1f).testTag(ScratchpadTags.UNSAVED),
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
            )
        } else {
            Box(Modifier.weight(1f))
        }
        IdeButton(
            text = "Save",
            onClick = { project.scope.launch { scratchpad.save() } },
            modifier = Modifier.testTag(ScratchpadTags.SAVE),
            primary = true,
            enabled = !scratchpad.saving,
        )
    }
}

// The body: the notes while they are read, an editable field once they are, and why a load or save failed while the
// field holds nothing to keep.
@Composable
private fun ScratchpadBody(scratchpad: ScratchpadState, modifier: Modifier = Modifier) {
    val failure = scratchpad.error
    when {
        scratchpad.loading -> {
            Message("Loading the notes…", ScratchpadTags.LOADING, IdeColors.TextSecondary, modifier)
        }
        failure != null && scratchpad.draft.isEmpty() -> {
            Message(failure, ScratchpadTags.ERROR, IdeColors.Error, modifier)
        }
        else -> {
            NotesField(scratchpad, Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxSize())
        }
    }
}

@Composable
private fun Message(text: String, tag: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.TopStart) {
        Label(text, Modifier.testTag(tag), color = color, fontSize = 12.sp)
    }
}

// The notes: a field of several lines, styled like the inputs of the kit (a filled box with a border), monospaced
// like a text file. What it holds is the draft of the state; the Save writes it.
@Composable
private fun NotesField(scratchpad: ScratchpadState, modifier: Modifier = Modifier) {
    BasicTextField(
        value = scratchpad.draft,
        onValueChange = { text -> scratchpad.edit(text) },
        modifier =
            modifier
                .background(IdeColors.Input, IdeShapes.Medium)
                .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
                .padding(10.dp)
                .testTag(ScratchpadTags.FIELD),
        textStyle = IdeFonts.Code.copy(color = IdeColors.Text, fontSize = NOTES_LINE.sp),
        cursorBrush = SolidColor(IdeColors.Caret),
    )
}
