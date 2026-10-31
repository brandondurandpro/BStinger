package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.state.ProjectState

// The IDE frame around the editors: toolbar, tool window strip and panels, splitters, status bar.

@Composable
internal fun Toolbar(app: AppState, project: ProjectState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(IdeColors.Panel)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectWidget(app, project)
        if (project.gitBranch == null) {
            Spacer(Modifier.weight(1f))
        } else {
            BranchWidget(project, Modifier.weight(1f).padding(start = 12.dp))
        }
        IconButton({ project.showPopup(PopupKind.GotoFile) }) { SearchIcon(color = IdeColors.Text) }
    }
}

@Composable
private fun ProjectWidget(app: AppState, project: ProjectState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable { menuOpen = true }
                .testTag(Tags.PROJECT_WIDGET)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectBadge(project.name)
            Label(project.name, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            Chevron(expanded = true, Modifier.padding(start = 2.dp))
        }
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open…", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    app.browseForProject()
                },
                modifier = Modifier.testTag(Tags.PROJECT_OPEN_OTHER),
            )
            DropdownMenuItem(
                text = { Text("Close Project", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    app.closeProject()
                },
                modifier = Modifier.testTag(Tags.PROJECT_CLOSE),
            )
        }
    }
}

@Composable
internal fun EmptyEditor() {
    Column(
        Modifier.fillMaxSize().testTag(Tags.EDITOR_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Hint("Go to File", "Ctrl+P  or  Ctrl+Shift+N  or  Double Shift")
        Hint("Recent Files", "Ctrl+E")
        Hint("Find in Files", "Ctrl+Shift+F")
        Hint("File Structure", "Ctrl+F12")
        Hint("Go to Symbol", "Ctrl+Alt+Shift+N")
        Hint("Project View", "Alt+1")
        Hint("Git", "Alt+9")
        Hint("Terminal", "Alt+F12")
        Hint("Open a file", "Double-click it in the Project view")
    }
}

@Composable
private fun Hint(action: String, shortcut: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Label(action, color = IdeColors.TextSecondary, modifier = Modifier.width(140.dp))
        Label(shortcut, color = IdeColors.Accent)
    }
}

@Composable
internal fun StatusBar(app: AppState, project: ProjectState) {
    val connection by app.connection.collectAsState()
    val tab = project.editor.activeTab
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(IdeColors.Panel)
            .padding(horizontal = 12.dp)
            .testTag(Tags.STATUS_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val path = listOf(project.name) + tab?.relativePath?.split('/').orEmpty()
        Label(
            path.joinToString("  ›  "),
            Modifier.weight(1f).testTag(Tags.STATUS_PATH),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        if (connection != ConnectionStatus.Connected) {
            Label(
                if (connection == ConnectionStatus.Reconnecting) "Server: reconnecting…" else "Server: disconnected",
                Modifier.testTag(Tags.STATUS_CONNECTION),
                color = IdeColors.Error,
                fontSize = 12.sp,
            )
        }
        tab?.document?.let { DocumentStatus(it) }
    }
}

@Composable
private fun DocumentStatus(document: Document) {
    val caret = document.caret
    // Cheap summary: never materialize a potentially huge selection just to count it.
    val range = document.selectionRange()
    val selection =
        when {
            range == null -> ""
            range.first.line == range.second.line -> " (${range.second.column - range.first.column} chars)"
            else -> " (${range.second.line - range.first.line + 1} lines)"
        }
    Label(
        "${caret.line + 1}:${caret.column + 1}$selection",
        Modifier.testTag(Tags.STATUS_CARET),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
    Label(if (document.lineSeparator == "\r\n") "CRLF" else "LF", color = IdeColors.TextSecondary, fontSize = 12.sp)
    Label("UTF-8", color = IdeColors.TextSecondary, fontSize = 12.sp)
    Label(document.language.name, color = IdeColors.TextSecondary, fontSize = 12.sp)
}
