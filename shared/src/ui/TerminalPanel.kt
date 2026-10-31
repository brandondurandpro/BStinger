package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.TerminalTab
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.closeOthers
import org.example.kotlineditor.state.closeToLeft
import org.example.kotlineditor.state.closeToRight

/** Terminal tool window (Alt+F12): tabs, and a real terminal drawing the grid the server sends. */
@Composable
internal fun TerminalPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val terminal = project.terminal
    LaunchedEffect(Unit) { terminal.ensureStarted() }
    Column(modifier.background(TerminalBackground).testTag(Tags.TERMINAL)) {
        ToolWindowHeader(
            "Terminal",
            ToolWindow.Terminal,
            project,
            drag,
            middle = { TerminalTabs(terminal, Modifier.weight(1f)) },
        ) {
            Label(
                if (terminal.active?.running == true) "Local" else "Stopped",
                Modifier.padding(end = 6.dp),
                color = IdeColors.TextSecondary,
            )
            IconButton({ terminal.newTab() }, Modifier.testTag(Tags.TERMINAL_NEW)) {
                PlusIcon()
            }
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Terminal, false) },
                Modifier.testTag(Tags.TERMINAL_HIDE),
            ) {
                MinimizeIcon()
            }
        }
        terminal.error?.let {
            Label(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = IdeColors.Error)
        }
        TerminalView(terminal, Modifier.weight(1f).fillMaxWidth())
    }
}

// The tabs scroll horizontally once they no longer fit, and the shown one is scrolled into view.
@Composable
private fun TerminalTabs(terminal: TerminalState, modifier: Modifier) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var contextMenu by remember { mutableStateOf<Pair<Int, DpOffset>?>(null) }
    // Also when the active tab's title changes: the shell sets it late, and a longer one can push the tab out.
    LaunchedEffect(terminal.activeIndex, terminal.tabs.size, terminal.active?.title) {
        val index = terminal.activeIndex
        if (index in terminal.tabs.indices && !isFullyVisible(listState, index)) listState.animateScrollToItem(index)
    }
    Box(modifier.fillMaxHeight().wheelScrollsHorizontally(listState).testTag(Tags.TERMINAL_TABS)) {
        LazyRow(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(terminal.tabs, key = { _, tab -> tab.number }) { index, tab ->
                TerminalTabChip(
                    tab,
                    active = index == terminal.activeIndex,
                    onSelect = { terminal.select(index) },
                    onClose = { terminal.close(index) },
                    onContextMenu = { position ->
                        contextMenu = tab.number to with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
                    },
                ) {
                    contextMenu?.takeIf { it.first == tab.number }?.let { (_, offset) ->
                        TerminalTabContextMenu(terminal, index, offset) { contextMenu = null }
                    }
                }
            }
        }
        HorizontalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp),
        )
    }
}

// The wheel scrolls the row of tabs, a plain vertical one as well as a horizontal one (touchpad, Shift+wheel).
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.wheelScrollsHorizontally(listState: LazyListState): Modifier =
    onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        event.changes.firstOrNull()?.let { change ->
            val delta = change.scrollDelta.let { if (it.x != 0f) it.x else it.y }
            if (delta != 0f) {
                listState.dispatchRawDelta(delta * WHEEL_STEP.toPx())
                change.consume()
            }
        }
    }

private val WHEEL_STEP = 48.dp

// Left click shows the tab, middle click closes it and right click opens its context menu, like editor tabs.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TerminalTabChip(
    tab: TerminalTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
) {
    val background = if (active) IdeColors.Selection else IdeColors.Panel
    val shape = RoundedCornerShape(4.dp)
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            onTabPress(event, onSelect, onClose, onContextMenu)
            // The tabs sit in the panel's header: a press on one must not start dragging the panel.
            event.changes.forEach { it.consume() }
        }
    Row(
        Modifier
            .padding(end = 4.dp)
            .background(background, shape)
            .then(press)
            .padding(start = 8.dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
            .testTag(Tags.terminalTab(tab.number)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(tab.title, color = if (active) IdeColors.Text else IdeColors.TextSecondary)
        IconButton(onClose, Modifier.padding(start = 4.dp).size(18.dp).testTag(Tags.terminalTabClose(tab.number))) {
            CloseIcon()
        }
        menu()
    }
}

// Context menu of a terminal tab: close it, the others, those on each side, or all of them.
@Composable
private fun TerminalTabContextMenu(terminal: TerminalState, index: Int, offset: DpOffset, onDismiss: () -> Unit) {
    val action = { block: () -> Unit ->
        onDismiss()
        block()
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.testTag(Tags.TERMINAL_TAB_MENU),
    ) {
        TabMenuItem("Close", Tags.MENU_CLOSE_TAB) { action { terminal.close(index) } }
        TabMenuItem(
            "Close Others",
            Tags.MENU_CLOSE_OTHER_TABS,
            enabled = terminal.tabs.size > 1,
        ) { action { terminal.closeOthers(index) } }
        TabMenuItem(
            "Close All to the Left",
            Tags.MENU_CLOSE_TABS_LEFT,
            enabled = index > 0,
        ) { action { terminal.closeToLeft(index) } }
        TabMenuItem(
            "Close All to the Right",
            Tags.MENU_CLOSE_TABS_RIGHT,
            enabled = index < terminal.tabs.lastIndex,
        ) { action { terminal.closeToRight(index) } }
        MenuSeparator()
        TabMenuItem("Close All", Tags.MENU_CLOSE_ALL_TABS) { action { terminal.closeAll() } }
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
    // Keyed by the tab too: a tab opened or shown later must get the panel's grid, not keep the default one.
    LaunchedEffect(tab, columns, rows, metrics) {
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
