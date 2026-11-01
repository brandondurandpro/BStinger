package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.pathName
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
        if (app.isRemote) {
            RemoteBadge(app)
        }
        // In agent mode, the branch of the project the agent of the active conversation works on.
        val branchProject = if (project.agentMode) project.agent.current?.workspace else project
        if (branchProject?.gitBranch == null) {
            Spacer(Modifier.weight(1f))
        } else {
            BranchWidget(branchProject, Modifier.weight(1f).padding(start = 12.dp))
        }
        if (app.agentModeEnabled) {
            ModeSwitch(project)
            Spacer(Modifier.width(10.dp))
        }
        IconButton({ project.showPopup(PopupKind.GotoFile) }) { SearchIcon(color = IdeColors.Text) }
    }
}

private val MENU_MIN_WIDTH = 320.dp
private val MENU_MAX_WIDTH = 900.dp

@Composable
private fun RemoteBadge(app: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    val connection by app.connection.collectAsState()
    val failure by app.remoteFailure.collectAsState()
    val failing = failure != null || connection != ConnectionStatus.Connected
    val border = if (failing) IdeColors.Error else IdeColors.Separator
    val label = app.remoteLabel ?: "remote"
    Box {
        Row(
            Modifier
                .border(1.dp, border, RoundedCornerShape(50))
                .clickable { menuOpen = true }
                .testTag(Tags.TOP_REMOTE)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Remote", color = IdeColors.TextSecondary)
            Label(
                label,
                color = if (failing) IdeColors.Error else IdeColors.Text,
                modifier = Modifier.padding(start = 6.dp),
            )
            Chevron(expanded = false)
        }
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Disconnect from $label", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    app.disconnectRemote()
                },
                modifier = Modifier.testTag(Tags.REMOTE_DISCONNECT),
            )
        }
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
        DropdownMenu(
            menuOpen,
            onDismissRequest = { menuOpen = false },
            Modifier
                .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                .testTag(Tags.PROJECT_MENU)
                .onPreviewKeyEvent { event ->
                    val escape = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                    if (escape) menuOpen = false
                    escape
                },
        ) {
            ProjectMenuItems(app) { menuOpen = false }
        }
    }
}

@Composable
private fun ProjectMenuItems(app: AppState, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Open Project…", fontSize = 13.sp) },
        onClick = {
            close()
            app.browseForProject()
        },
        modifier = Modifier.testTag(Tags.PROJECT_OPEN_OTHER),
    )
    DropdownMenuItem(
        text = { Text("Go Homepage", fontSize = 13.sp) },
        onClick = {
            close()
            app.closeProject()
        },
        modifier = Modifier.testTag(Tags.PROJECT_CLOSE),
    )
    // Feature flag of the experimental agent mode, saved with the preferences.
    DropdownMenuItem(
        text = {
            val check = if (app.agentModeEnabled) "✓  " else "     "
            Text("${check}Agent Mode (Experimental)", fontSize = 13.sp)
        },
        onClick = {
            close()
            app.enableAgentMode(!app.agentModeEnabled)
        },
        modifier = Modifier.testTag(Tags.PROJECT_AGENT_MODE),
    )
    val others = app.otherProjects
    if (others.isNotEmpty()) {
        HorizontalDivider(color = IdeColors.Border)
        Label(
            "Recent Projects",
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        others.forEach { path ->
            RecentProjectItem(path) {
                close()
                app.openProject(path)
            }
        }
    }
}

// Not a DropdownMenuItem: Material caps those at 280 dp, which cut the paths. This row is as wide as its full path.
@Composable
private fun RecentProjectItem(path: String, onClick: () -> Unit) {
    val name = pathName(path)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) IdeColors.Hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .testTag(ItemTags.switchProject(path))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectBadge(name)
        Column(Modifier.padding(start = 10.dp)) {
            Label(name, fontSize = 13.sp)
            Label(path, color = IdeColors.TextSecondary, fontSize = 11.sp, maxLines = Int.MAX_VALUE)
        }
    }
}

@Composable
internal fun EmptyEditor() {
    // Center the block as a whole: centering each row individually staggers them, because every
    // row is as wide as its own shortcut text.
    Box(
        Modifier.fillMaxSize().testTag(Tags.EDITOR_EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        Column {
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
}

@Composable
private fun Hint(action: String, shortcut: String) {
    Row(
        Modifier
            .testTag(ItemTags.editorHint(action))
            .padding(vertical = 5.dp),
    ) {
        Label(action, color = IdeColors.TextSecondary, modifier = Modifier.width(140.dp))
        Label(shortcut, color = IdeColors.Accent, modifier = Modifier.testTag(ItemTags.editorHintShortcut(action)))
    }
}

@Composable
internal fun StatusBar(app: AppState, project: ProjectState) {
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
        val path =
            if (project.agentMode) {
                val active = project.agent.current
                val editor = active?.workspace?.editor
                val file = editor?.activeTab?.takeUnless { active.showsChat }
                listOfNotNull(project.name, "Agent", active?.title) + file?.relativePath?.split('/').orEmpty()
            } else {
                listOf(project.name) + tab?.relativePath?.split('/').orEmpty()
            }
        Label(
            path.joinToString("  ›  "),
            Modifier.weight(1f).testTag(Tags.STATUS_PATH),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        app.remoteLabel?.let {
            Label(
                "Remote: $it",
                Modifier.testTag(Tags.STATUS_REMOTE),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        app.opening?.let {
            Label(
                "Opening ${pathName(it)}…",
                Modifier.testTag(Tags.STATUS_OPENING),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        app.error?.let {
            Label(it, Modifier.testTag(Tags.STATUS_ERROR), color = IdeColors.Error, fontSize = 12.sp)
        }
        LanguageServerStatusItems(project)
        LinkStatus(app)
        if (!project.agentMode) tab?.document?.let { DocumentStatus(it) }
    }
}

// What is wrong with the link to the server, if anything: SSH refusing the host first, then the protocol client.
@Composable
private fun LinkStatus(app: AppState) {
    val connection by app.connection.collectAsState()
    val remoteFailure by app.remoteFailure.collectAsState()
    val message =
        when {
            remoteFailure != null -> "SSH: $remoteFailure"
            connection == ConnectionStatus.Reconnecting -> "Server: reconnecting…"
            connection != ConnectionStatus.Connected -> "Server: disconnected"
            else -> null
        }
    message?.let {
        Label(it, Modifier.testTag(Tags.STATUS_CONNECTION), color = IdeColors.Error, fontSize = 12.sp)
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
    Label(
        document.syntax?.spec?.name ?: document.language.name,
        Modifier.testTag(Tags.STATUS_LANGUAGE),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
}
