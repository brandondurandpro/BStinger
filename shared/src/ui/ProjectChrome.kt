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
import androidx.compose.ui.draw.clip
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
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.workspaceProject

// The IDE frame around the editors: toolbar, tool window strip and panels, splitters, status bar.

@Composable
internal fun Toolbar(app: AppState, project: ProjectState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(IdeColors.Frame)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectWidget(app, project)
        if (app.session.isRemote) {
            RemoteBadge(app)
        }
        // In agent mode, the branch of the project the agent of the active conversation works on.
        val branchProject = if (project.agentMode) project.agent.current?.workspaceProject else project
        if (branchProject == null || branchProject.git.currentBranch == null) {
            Spacer(Modifier.weight(1f))
        } else {
            BranchWidget(branchProject, Modifier.weight(1f).padding(start = 12.dp))
        }
        if (app.settings.agentModeEnabled) {
            ModeSwitch(project.agentMode) { project.agentMode = it }
            Spacer(Modifier.width(10.dp))
        }
        RunWidget(project.run, { project.showToolWindow(ToolWindow.Run) })
        Spacer(Modifier.width(10.dp))
        IdeButton("Deploy", { project.deployments.show() }, Modifier.testTag(AppTags.DEPLOY_BUTTON))
        Spacer(Modifier.width(6.dp))
        SearchField { project.showPopup(PopupKind.GotoFile) }
    }
}

// Longer names end in an ellipsis, leaving room for the branch and its pinned branches.
private val PROJECT_NAME_WIDTH = 180.dp
private val MENU_MIN_WIDTH = 320.dp
private val MENU_MAX_WIDTH = 900.dp

@Composable
private fun RemoteBadge(app: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    val connection by app.session.connection.collectAsState()
    val failure by app.session.remoteFailure.collectAsState()
    val failing = failure != null || connection != ConnectionStatus.Connected
    val border = if (failing) IdeColors.Error else IdeColors.Separator
    val label = app.session.remoteLabel ?: "remote"
    Box {
        Row(
            Modifier
                .border(1.dp, border, RoundedCornerShape(50))
                .clickable { menuOpen = true }
                .testTag(AppTags.TOP_REMOTE)
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
        IdeDropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            IdeMenuItem(
                text = { Text("Disconnect from $label", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    app.disconnectRemote()
                },
                modifier = Modifier.testTag(AppTags.REMOTE_DISCONNECT),
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
                .height(28.dp)
                .border(1.dp, IdeColors.Border, IdeShapes.Medium)
                .clip(IdeShapes.Medium)
                .clickable { menuOpen = true }
                .testTag(AppTags.PROJECT_WIDGET)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectBadge(project.name)
            Label(
                project.name,
                Modifier.padding(start = 8.dp).widthIn(max = PROJECT_NAME_WIDTH),
                fontWeight = FontWeight.SemiBold,
            )
            Chevron(expanded = true, Modifier.padding(start = 2.dp))
        }
        IdeDropdownMenu(
            menuOpen,
            onDismissRequest = { menuOpen = false },
            Modifier
                .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                .testTag(AppTags.PROJECT_MENU)
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
private fun SettingsMenuItem(app: AppState, close: () -> Unit) {
    IdeMenuItem(
        text = { Text("Settings…", fontSize = 13.sp) },
        trailingIcon = { Text("Ctrl+Alt+S", fontSize = 12.sp, color = IdeColors.TextSecondary) },
        onClick = {
            close()
            app.settings.open()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_SETTINGS),
    )
}

@Composable
private fun ProjectMenuItems(app: AppState, close: () -> Unit) {
    IdeMenuItem(
        text = { Text("Open Project…", fontSize = 13.sp) },
        onClick = {
            close()
            app.session.browseForProject()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_OPEN_OTHER),
    )
    IdeMenuItem(
        text = { Text("Go Homepage", fontSize = 13.sp) },
        onClick = {
            close()
            app.session.closeProject()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_CLOSE),
    )
    SettingsMenuItem(app, close)
    // Feature flag of the experimental agent mode, saved with the preferences.
    IdeMenuItem(
        text = {
            val check = if (app.settings.agentModeEnabled) "✓  " else "     "
            Text("${check}Agent Mode (Experimental)", fontSize = 13.sp)
        },
        onClick = {
            close()
            app.settings.enableAgentMode(!app.settings.agentModeEnabled)
        },
        modifier = Modifier.testTag(AppTags.PROJECT_AGENT_MODE),
    )
    // The telemetry consent, also asked by the welcome screen: takes effect at once.
    if (!app.settings.telemetry.forbidden) {
        IdeMenuItem(
            text = {
                val check = if (app.settings.telemetry.enabled) "✓  " else "     "
                Text("${check}Share Performance Measures", fontSize = 13.sp)
            },
            onClick = {
                close()
                app.settings.telemetry.choose(!app.settings.telemetry.enabled)
            },
            modifier = Modifier.testTag(AppTags.PROJECT_TELEMETRY),
        )
    }
    val others = app.session.otherProjects
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
                app.session.openProject(path)
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
            .testTag(AppTags.switchProject(path))
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
        Modifier.fillMaxSize().testTag(AppTags.EDITOR_EMPTY),
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
            .testTag(AppTags.editorHint(action))
            .padding(vertical = 5.dp),
    ) {
        Label(action, color = IdeColors.TextSecondary, modifier = Modifier.width(140.dp))
        Label(shortcut, color = IdeColors.Accent, modifier = Modifier.testTag(AppTags.editorHintShortcut(action)))
    }
}

@Composable
internal fun StatusBar(app: AppState, project: ProjectState) {
    val tab = project.editor.activeTab
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(IdeColors.Frame)
            .padding(horizontal = 12.dp)
            .testTag(AppTags.STATUS_BAR),
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
            Modifier.weight(1f).testTag(AppTags.STATUS_PATH),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        app.session.remoteLabel?.let {
            Label(
                "Remote: $it",
                Modifier.testTag(AppTags.STATUS_REMOTE),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        app.session.opening?.let {
            Label(
                "Opening ${pathName(it)}…",
                Modifier.testTag(AppTags.STATUS_OPENING),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        app.session.error?.let {
            Label(it, Modifier.testTag(AppTags.STATUS_ERROR), color = IdeColors.Error, fontSize = 12.sp)
        }
        LanguageServerStatusItems(project)
        LinkStatus(app)
        if (!project.agentMode) tab?.document?.let { DocumentStatus(it) }
    }
}

// What is wrong with the link to the server, if anything: SSH refusing the host first, then the protocol client.
@Composable
private fun LinkStatus(app: AppState) {
    val connection by app.session.connection.collectAsState()
    val remoteFailure by app.session.remoteFailure.collectAsState()
    val message =
        when {
            remoteFailure != null -> "SSH: $remoteFailure"
            connection == ConnectionStatus.Reconnecting -> "Server: reconnecting…"
            connection != ConnectionStatus.Connected -> "Server: disconnected"
            else -> null
        }
    message?.let {
        Label(it, Modifier.testTag(AppTags.STATUS_CONNECTION), color = IdeColors.Error, fontSize = 12.sp)
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
        Modifier.testTag(AppTags.STATUS_CARET),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
    Label(if (document.lineSeparator == "\r\n") "CRLF" else "LF", color = IdeColors.TextSecondary, fontSize = 12.sp)
    Label("UTF-8", color = IdeColors.TextSecondary, fontSize = 12.sp)
    Label(
        document.syntax?.spec?.name ?: document.language.name,
        Modifier.testTag(AppTags.STATUS_LANGUAGE),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
}
