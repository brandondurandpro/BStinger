package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.ToolWindow

/** Terminal tool window (Alt+F12): scrollback (virtualized) and a command line at the bottom. */
@Composable
internal fun TerminalPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val terminal = project.terminal
    val onHide = { project.toolWindows.setVisible(ToolWindow.Terminal, false) }
    // Follow the output, like a terminal.
    LaunchedEffect(terminal.lines.size) {
        if (terminal.lines.isNotEmpty()) terminal.listState.scrollToItem(terminal.lines.lastIndex)
    }
    Column(modifier.background(IdeColors.Editor).testTag(Tags.TERMINAL)) {
        ToolWindowHeader("Terminal", ToolWindow.Terminal, project, drag) {
            Label(
                if (terminal.running) "Local" else "Stopped",
                Modifier.padding(end = 6.dp),
                color = IdeColors.TextSecondary,
            )
            IconButton(
                terminal::clear,
                Modifier.testTag(Tags.TERMINAL_CLEAR),
            ) { Label("⌫", color = IdeColors.TextSecondary) }
            IconButton(onHide, Modifier.testTag(Tags.TERMINAL_HIDE)) { CloseIcon() }
        }
        TerminalOutput(terminal)
        CommandLine(terminal, onHide)
    }
}

@Composable
private fun ColumnScope.TerminalOutput(terminal: TerminalState) {
    val codeStyle = LocalCodeStyle.current
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = terminal.listState) {
            items(terminal.lines.size) { index ->
                BasicText(
                    terminal.lines[index],
                    Modifier.testTag(ItemTags.terminalLine(index)),
                    style = codeStyle,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(terminal.listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun CommandLine(terminal: TerminalState, onHide: () -> Unit) {
    var command by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val codeStyle = LocalCodeStyle.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("$", Modifier.padding(end = 6.dp), style = codeStyle.copy(color = IdeColors.StringLiteral))
        IdeTextField(
            command,
            { command = it },
            Modifier
                .weight(1f)
                .testTag(Tags.TERMINAL_INPUT)
                .focusRequester(focus)
                .popupKeys(
                    onEnter = {
                        terminal.send(command)
                        command = ""
                    },
                    onClose = onHide,
                    // Up/Down walk through the history of commands.
                    onMove = { delta -> terminal.historyStep(delta)?.let { command = it } },
                ),
            placeholder = "Type a command and press Enter",
            monospace = true,
        )
    }
}
