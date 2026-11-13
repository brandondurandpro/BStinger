package org.example.kotlineditor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.LspLevel
import org.example.kotlineditor.state.LspLevelSource
import org.example.kotlineditor.state.ProjectState

/**
 * The level of the language servers in force (Off, Basic or Full) and where it was chosen; a click asks the level,
 * then whether it is for the conversation shown (in agent mode), the project, or every project.
 */
@Composable
internal fun LspLevelWidget(app: AppState, project: ProjectState) {
    var open by remember { mutableStateOf(false) }
    var chosen: LspLevel? by remember { mutableStateOf(null) }
    val close = {
        open = false
        chosen = null
    }
    Box {
        Row(
            Modifier
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(50))
                .clickable { open = true }
                .testTag(AppTags.LSP_LEVEL_BUTTON)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("LSP", color = IdeColors.TextSecondary)
            Label(project.lspLevel.title, modifier = Modifier.padding(start = 6.dp))
            val source = project.lspLevelSource.title
            Label(source, color = IdeColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
        }
        IdeDropdownMenu(open, onDismissRequest = close) {
            for (level in LspLevel.entries) {
                LevelItem(app, project, level, expanded = chosen == level, close) { chosen = it }
            }
        }
    }
}

// A level and a ">" saying it asks more: its click shows, under it in the same menu, where the level applies.
@Composable
private fun LevelItem(
    app: AppState,
    project: ProjectState,
    level: LspLevel,
    expanded: Boolean,
    close: () -> Unit,
    expand: (LspLevel?) -> Unit,
) {
    val check = if (project.lspLevel == level) "✓  " else "     "
    IdeMenuItem(
        text = { Text("$check${level.title}: ${level.description}", fontSize = 13.sp) },
        onClick = { expand(if (expanded) null else level) },
        trailingIcon = { Text(if (expanded) "⌄" else ">", fontSize = 13.sp) },
        modifier = Modifier.testTag(AppTags.lspLevel(level.name)),
    )
    if (expanded) ScopeItems(app, project, level, close)
}

@Composable
private fun ScopeItems(app: AppState, project: ProjectState, level: LspLevel, close: () -> Unit) {
    val inAgent = project.agentMode && project.agent.current != null
    if (inAgent) {
        IdeMenuItem(
            text = { Text("      For this conversation", fontSize = 13.sp) },
            onClick = {
                project.chooseConversationLspLevel(level)
                close()
            },
            modifier = Modifier.testTag(AppTags.lspScope(LspLevelSource.Conversation.name)),
        )
    }
    IdeMenuItem(
        text = { Text("      For this project", fontSize = 13.sp) },
        onClick = {
            project.chooseConversationLspLevel(null)
            project.projectLspLevel = level
            close()
        },
        modifier = Modifier.testTag(AppTags.lspScope(LspLevelSource.Project.name)),
    )
    IdeMenuItem(
        text = { Text("      For every project", fontSize = 13.sp) },
        onClick = {
            project.chooseConversationLspLevel(null)
            project.projectLspLevel = null
            app.settings.lspLevel = level
            close()
        },
        modifier = Modifier.testTag(AppTags.lspScope(LspLevelSource.Global.name)),
    )
}
