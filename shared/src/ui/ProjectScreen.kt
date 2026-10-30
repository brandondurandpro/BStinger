package org.example.kotlineditor.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.FindState
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.findAll
import org.example.kotlineditor.state.gotoDeclaration
import org.example.kotlineditor.state.saveAll
import org.example.kotlineditor.state.synchronize
import org.example.kotlineditor.state.visualColumn

@Composable
fun ProjectScreen(app: AppState, project: ProjectState) {
    val editorFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val shortcuts = remember(project) { GlobalShortcuts(IdeContext(app, project, editorFocus, findFocus)) }
    SyncOnWindowFocus(project)
    Column(
        Modifier
            .fillMaxSize()
            .background(IdeColors.Panel)
            .testTag(Tags.PROJECT)
            .onPreviewKeyEvent(shortcuts::handle),
    ) {
        Toolbar(app, project)
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
        Box(Modifier.weight(1f)) {
            ProjectBody(app, project, editorFocus, findFocus)
            ProjectPopups(project) { closePopup(project, editorFocus) }
            project.fileDialog?.let { dialog ->
                FileDialogPopup(project, dialog, onClose = { project.fileDialog = null })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Border))
        StatusBar(app, project)
    }
}

// IntelliJ saves all files when the window loses focus, and synchronizes with the disk when it gets it back.
@Composable
private fun SyncOnWindowFocus(project: ProjectState) {
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
private fun ProjectBody(app: AppState, project: ProjectState, editorFocus: FocusRequester, findFocus: FocusRequester) {
    val drag = remember { ToolWindowDrag() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(Tags.WORKSPACE)
            .onGloballyPositioned { drag.workspace = it.boundsInRoot() },
    ) {
        Row(Modifier.fillMaxSize()) {
            ToolStrip(project)
            VerticalDivider(Modifier.fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ToolDock(project, Dock.Left, drag)
                    EditorArea(app, project, editorFocus, findFocus, Modifier.weight(1f))
                    ToolDock(project, Dock.Right, drag)
                }
                ToolDock(project, Dock.Bottom, drag)
            }
        }
        DropOverlay(drag)
    }
}

@Composable
private fun ProjectPopups(project: ProjectState, onClose: () -> Unit) {
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

private fun closePopup(project: ProjectState, editorFocus: FocusRequester) {
    project.popup = null
    if (project.editor.activeTab?.document != null) runCatching { editorFocus.requestFocus() }
}

@Composable
private fun EditorArea(
    app: AppState,
    project: ProjectState,
    editorFocus: FocusRequester,
    findFocus: FocusRequester,
    modifier: Modifier,
) {
    val editor = project.editor
    val tab = editor.activeTab
    Column(modifier.fillMaxHeight().background(IdeColors.Editor)) {
        if (editor.tabs.isEmpty()) {
            EmptyEditor()
        } else {
            EditorTabs(editor)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            tab?.let { key(it) { TabContent(app, project, it, editorFocus, findFocus) } }
        }
    }
}

@Composable
private fun TabContent(
    app: AppState,
    project: ProjectState,
    tab: EditorTab,
    editorFocus: FocusRequester,
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
            ReadyEditor(app, project, tab, status.document, editorFocus, findFocus)
        }

        is TabStatus.Diff -> {
            DiffView(
                tab,
                status.lines,
                sideBySide = project.diffSideBySide,
                onToggleSideBySide = { project.diffSideBySide = !project.diffSideBySide },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReadyEditor(
    app: AppState,
    project: ProjectState,
    tab: EditorTab,
    document: Document,
    editorFocus: FocusRequester,
    findFocus: FocusRequester,
) {
    val find = project.find
    val highlights = rememberSearchHighlights(project, document)
    if (find.visible) {
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
