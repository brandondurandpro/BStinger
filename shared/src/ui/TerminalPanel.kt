package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.TerminalTab
import org.example.kotlineditor.state.ToolWindow

/** Terminal tool window (Alt+F12): tabs, and a real terminal drawing the grid the server sends. */
@Composable
internal fun TerminalPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val terminal = project.terminal
    LaunchedEffect(Unit) { terminal.ensureStarted() }
    Column(modifier.background(TerminalBackground).testTag(Tags.TERMINAL)) {
        ToolWindowHeader("Terminal", ToolWindow.Terminal, project, drag) {
            Label(
                if (terminal.active?.running == true) "Local" else "Stopped",
                Modifier.padding(end = 6.dp),
                color = IdeColors.TextSecondary,
            )
            IconButton(terminal::clear, Modifier.testTag(Tags.TERMINAL_CLEAR)) {
                Label("⌫", color = IdeColors.TextSecondary)
            }
            IconButton(terminal::closeActive, Modifier.testTag(Tags.TERMINAL_CLOSE)) {
                Label("×", color = IdeColors.TextSecondary)
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Terminal, false) },
                Modifier.testTag(Tags.TERMINAL_HIDE),
            ) {
                CloseIcon()
            }
        }
        TerminalTabs(terminal)
        terminal.error?.let {
            Label(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = IdeColors.Error)
        }
        TerminalView(terminal, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun TerminalTabs(terminal: TerminalState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        terminal.tabs.forEachIndexed { index, tab ->
            TerminalTabChip(tab, index == terminal.activeIndex) { terminal.select(index) }
        }
        IconButton({ terminal.newTab() }, Modifier.testTag(Tags.TERMINAL_NEW)) {
            Label("+", color = IdeColors.TextSecondary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TerminalTabChip(tab: TerminalTab, active: Boolean, onSelect: () -> Unit) {
    val background = if (active) IdeColors.Selection else IdeColors.Panel
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(end = 4.dp)
            .background(background, shape)
            .pointerInput(tab, onSelect) { detectTapGestures { onSelect() } }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(Tags.terminalTab(tab.number)),
    ) {
        Label(tab.title, color = if (active) IdeColors.Text else IdeColors.TextSecondary)
    }
}

@Composable
private fun TerminalView(terminal: TerminalState, modifier: Modifier) {
    val tab = terminal.active
    if (tab == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Label("No terminal", color = IdeColors.TextSecondary)
        }
        return
    }
    val style = LocalCodeStyle.current
    val textMeasurer = rememberTextMeasurer()
    val metrics = rememberCellMetrics(textMeasurer, style)
    val focus = remember { FocusRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val selection = remember(tab) { TerminalSelection() }
    val rows = metrics.rows(size)

    TerminalLifecycle(tab, terminal, focus, metrics, size)
    Canvas(
        modifier
            .graphicsLayer()
            .onSizeChanged { size = it }
            .focusRequester(focus)
            .onFocusChanged { terminal.focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event -> handleTerminalKey(event, terminal, tab, selection) }
            .terminalPointerInput(tab, focus, metrics, selection)
            .terminalTextInput(tab)
            .testTag(Tags.TERMINAL_INPUT),
    ) {
        drawTerminal(tab.snapshot, textMeasurer, style, metrics, rows, selection)
    }
}

@Composable
private fun TerminalLifecycle(
    tab: TerminalTab,
    terminal: TerminalState,
    focus: FocusRequester,
    metrics: CellMetrics,
    size: IntSize,
) {
    LaunchedEffect(tab) { runCatching { focus.requestFocus() } }
    val columns = metrics.columns(size)
    val rows = metrics.rows(size)
    LaunchedEffect(columns, rows, metrics) {
        if (columns > 0 && rows > 0) tab.resize(columns, rows, metrics.width, metrics.height)
    }
    DisposableEffect(terminal) {
        onDispose { terminal.focused = false }
    }
}

@Composable
private fun rememberCellMetrics(textMeasurer: TextMeasurer, style: TextStyle): CellMetrics {
    val density = LocalDensity.current
    return remember(textMeasurer, style, density) {
        val lineHeight = style.lineHeight.takeOrElse { style.fontSize * 1.25f }
        CellMetrics(
            textMeasurer.measure("M", style).size.width,
            with(density) { lineHeight.toPx() }.toInt(),
        )
    }
}
