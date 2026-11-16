package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.EDITOR_SERVICE
import org.example.kotlineditor.state.EditorState
import org.example.kotlineditor.state.OutlineState
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.syntax.Declaration

/** The Outline tool window, its id saved in the layout preferences. */
val OutlineWindow = ToolWindow("Outline")

/** The Outline window: the declarations of the active file, indented by nesting; a click goes to one. */
fun outlineToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(OutlineWindow, Dock.Right),
        tag = OutlineTags.TOOL_BUTTON,
        group = StripGroup.Last,
        icon = Lucide.ListFilter,
        content = { host, drag, modifier -> OutlinePanel(host, drag, modifier) },
    )

/** The panel of the Outline window: it follows the active tab, with no state of its own beyond its result. */
@Composable
fun OutlinePanel(host: ProjectHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val state = remember { OutlineState() }
    val editor = host.service(EDITOR_SERVICE)
    val tab = editor.activeTab
    val document = tab?.document
    LaunchedEffect(document) { state.update(host, tab, document) }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(OutlineTags.PANEL)) {
        ToolWindowHeader("Outline", OutlineWindow, host.toolWindows, drag)
        when {
            tab == null || document == null -> OutlineMessage(OutlineTags.NO_FILE, "No file to outline")
            !state.isCurrent(tab.relativePath) -> OutlineMessage(OutlineTags.LOADING, "Loading…")
            state.outline.isEmpty() -> OutlineMessage(OutlineTags.EMPTY, "No declarations")
            else -> OutlineList(state.outline) { declaration -> navigateTo(editor, host, declaration) }
        }
    }
}

/** Moves the caret of the active document to the name of [declaration], remembering the position for Back. */
private fun navigateTo(editor: EditorState, host: ProjectHost, declaration: Declaration) {
    val tab = editor.activeTab ?: return
    val landing = TextPos(declaration.line, declaration.column)
    host.navigate(tab.entry, landing, landing)
}

@Composable
private fun OutlineList(declarations: List<Declaration>, open: (Declaration) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(declarations) { declaration ->
            DeclarationRow(declaration) { open(declaration) }
        }
    }
}

@Composable
private fun DeclarationRow(declaration: Declaration, onClick: () -> Unit) {
    // One nesting level is one 16 dp step, like the File Structure popup indents.
    val indent = 16.dp * declaration.depth
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(OutlineTags.item(declaration.name, declaration.line))
            .padding(start = 8.dp + indent, top = 3.dp, bottom = 3.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(declaration.kind)
        Label(declaration.name, Modifier.padding(start = 8.dp))
        Label(
            ":${declaration.line + 1}",
            Modifier.padding(start = 6.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun OutlineMessage(tag: String, text: String) {
    Box(Modifier.fillMaxSize().padding(12.dp).testTag(tag)) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

