package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorPane
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.FindState
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.findAll
import org.example.kotlineditor.state.gotoDeclaration
import org.example.kotlineditor.state.openFileOfDiff
import org.example.kotlineditor.state.saveAll
import org.example.kotlineditor.state.synchronize
import org.example.kotlineditor.state.visualColumn

@Composable
fun ProjectScreen(app: AppState, project: ProjectState) {
    val findFocus = remember { FocusRequester() }
    val shortcuts = remember(project) { GlobalShortcuts(IdeContext(app, project, findFocus)) }
    val rootFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    SyncOnWindowFocus(project)
    // Shortcuts only reach a focused component: with no file open nothing else holds the focus, so the screen takes it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // With the Open Project dialog open, its own field keeps the keyboard.
    val screenFocusFree = project.popup == null && !project.branchPickerOpen && !app.projectPickerOpen
    LaunchedEffect(hasFocus, screenFocusFree, windowFocused) {
        if (!hasFocus && screenFocusFree) runCatching { rootFocus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(IdeColors.Panel)
            .testTag(Tags.PROJECT)
            .onPreviewKeyEvent(shortcuts::handle)
            .focusRequester(rootFocus)
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusable(),
    ) {
        Toolbar(app, project)
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
        Box(Modifier.weight(1f)) {
            if (project.agentMode) {
                AgentWorkspace(app, project)
            } else {
                ProjectBody(app, project, findFocus)
                ProjectPopups(project) { closePopup(project) }
                if (project.branchPickerOpen) BranchPicker(project)
            }
            project.fileDialog?.let { dialog ->
                FileDialogPopup(project, dialog, onClose = { project.fileDialog = null })
            }
            if (!project.agentMode) LanguageServerChoiceDialog(project)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
        StatusBar(app, project)
    }
}

// IntelliJ saves all files when the window loses focus, and synchronizes with the disk when it gets it back.
@Composable
internal fun SyncOnWindowFocus(project: ProjectState) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var wasFocused by remember { mutableStateOf(windowFocused) }
    LaunchedEffect(windowFocused) {
        when {
            !windowFocused -> project.editor.saveAll()
            !wasFocused -> project.synchronize()
            else -> Unit
        }
        wasFocused = windowFocused
    }
}

@Composable
private fun ProjectBody(app: AppState, project: ProjectState, findFocus: FocusRequester) {
    val drag = remember { ToolWindowDrag() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(Tags.WORKSPACE)
            .onGloballyPositioned { drag.workspace = it.boundsInRoot() },
    ) {
        Row(Modifier.fillMaxSize()) {
            ToolStrip(project, app.devtools)
            VerticalDivider(Modifier.fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ToolDock(project, Dock.Left, drag)
                    Column(Modifier.weight(1f)) {
                        LanguageServerChoiceBanner(project)
                        EditorArea(app, project, findFocus, Modifier.weight(1f))
                    }
                    ToolDock(project, Dock.Right, drag)
                }
                ToolDock(project, Dock.Bottom, drag)
            }
        }
        DropOverlay(drag)
    }
}

@Composable
internal fun ProjectPopups(project: ProjectState, onClose: () -> Unit) {
    val document = project.editor.activeTab?.document
    when (project.popup) {
        PopupKind.GotoFile -> GotoFilePopup(project, onClose = onClose)
        PopupKind.RecentFiles -> GotoFilePopup(project, onClose = onClose, recentOnly = true)
        PopupKind.FindInFiles -> FindInFilesPopup(project, onClose = onClose)
        PopupKind.GotoLine -> document?.let { GotoLinePopup(it, onClose = onClose) }
        PopupKind.GotoSymbol -> GotoSymbolPopup(project, onClose = onClose)
        PopupKind.FileStructure -> document?.let { FileStructurePopup(it, onClose = onClose) }
        null -> Unit
    }
}

internal fun closePopup(project: ProjectState) {
    project.popup = null
    val editor = project.editor
    if (editor.activeTab?.document != null) runCatching { editor.activePane.focusRequester.requestFocus() }
}

@Composable
internal fun EditorArea(app: AppState, project: ProjectState, findFocus: FocusRequester, modifier: Modifier) {
    val editor = project.editor
    Row(modifier.fillMaxHeight().background(IdeColors.Editor)) {
        editor.panes.forEachIndexed { index, pane ->
            if (index > 0) VerticalDivider(Modifier.fillMaxHeight())
            key(pane) { EditorPaneView(app, project, pane, index, findFocus, Modifier.weight(1f)) }
        }
    }
}

// One editor split: its tab strip and the active tab's content. Pressing anywhere in it makes it the active pane.
@Composable
private fun EditorPaneView(
    app: AppState,
    project: ProjectState,
    pane: EditorPane,
    index: Int,
    findFocus: FocusRequester,
    modifier: Modifier,
) {
    val editor = project.editor
    val tab = pane.activeTab
    Column(
        modifier
            .fillMaxHeight()
            .testTag(Tags.pane(index))
            .pointerInput(pane) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    editor.activatePane(pane)
                }
            },
    ) {
        if (pane.tabs.isEmpty()) {
            EmptyEditor()
        } else {
            EditorTabs(editor, pane)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            tab?.let { key(it) { TabContent(app, project, pane, it, findFocus) } }
        }
    }
}

@Composable
private fun TabContent(
    app: AppState,
    project: ProjectState,
    pane: EditorPane,
    tab: EditorTab,
    findFocus: FocusRequester,
) {
    when (val status = tab.status) {
        TabStatus.Loading -> {
            Box(Modifier.fillMaxSize().testTag(Tags.EDITOR_LOADING))
        }

        is TabStatus.Unsupported -> {
            EditorMessage(status.message)
        }

        is TabStatus.Ready -> {
            ReadyEditor(app, project, pane, tab, status.document, findFocus)
        }

        is TabStatus.Diff -> {
            DiffView(
                tab,
                status.lines,
                sideBySide = project.diffSideBySide,
                onToggleSideBySide = { project.diffSideBySide = !project.diffSideBySide },
                onOpenFile = { project.openFileOfDiff(tab) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReadyEditor(
    app: AppState,
    project: ProjectState,
    pane: EditorPane,
    tab: EditorTab,
    document: Document,
    findFocus: FocusRequester,
) {
    val find = project.find
    val highlights = rememberSearchHighlights(project, document)
    val editorFocus = pane.focusRequester
    if (find.visible && project.editor.activePane == pane) {
        FindBar(find, document, findFocus, onClose = {
            find.visible = false
            find.replaceVisible = false
            runCatching { editorFocus.requestFocus() }
        })
    }
    LaunchedEffect(Unit) {
        if (!find.visible && project.popup == null) runCatching { editorFocus.requestFocus() }
    }
    CodeEditor(
        tab,
        document,
        editorFocus,
        Modifier.fillMaxSize(),
        highlights.takeIf { find.visible },
        fontSize = app.editorFontSize,
        onZoom = app::zoomEditor,
        onCtrlClick = project::gotoDeclaration,
        completion = project.completion.takeIf { Languages.languageOf(tab.entry.path) != null },
        path = tab.entry.path,
    )
}

@Composable
private fun rememberSearchHighlights(project: ProjectState, document: Document): SearchHighlights? {
    val find = project.find
    var highlights by remember(document) { mutableStateOf<SearchHighlights?>(null) }
    LaunchedEffect(document, find.visible, find.query, find.matchCase, document.version) {
        val query = find.query
        val active = find.visible && query.isNotEmpty()
        val matches =
            if (active) {
                withContext(Dispatchers.Default) { document.findAll(query, ignoreCase = !find.matchCase) }
            } else {
                emptyList()
            }
        find.setMatches(matches, document.selectionRange()?.first ?: document.caret)
        if (active && find.pendingSelectNext) {
            find.pendingSelectNext = false
            find.selectCurrent(document)
        }
    }
    // Rebuild the per-line map whenever matches or the current match change.
    LaunchedEffect(find.matches, find.current) {
        val matches = find.matches
        highlights =
            if (matches.isEmpty()) {
                null
            } else {
                withContext(Dispatchers.Default) { highlightsOf(document, matches, find) }
            }
    }
    return highlights
}

private fun highlightsOf(document: Document, matches: List<TextPos>, find: FindState): SearchHighlights {
    val byLine = HashMap<Int, IntArray>()
    for ((line, list) in matches.groupBy { it.line }) {
        val text = document.lines.getOrNull(line)
        if (text != null) byLine[line] = IntArray(list.size) { visualColumn(text, list[it].column) }
    }
    return SearchHighlights(byLine, find.query.length, matches.getOrNull(find.current))
}
