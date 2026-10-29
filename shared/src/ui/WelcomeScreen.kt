package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.state.AppState

@Composable
fun WelcomeScreen(state: AppState) {
    Row(Modifier.fillMaxSize().background(IdeColors.Panel).testTag(Tags.WELCOME)) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectBadge("Kotlin Editor", size = 36.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Label("Kotlin Editor", fontWeight = FontWeight.SemiBold)
                    Label("2026.1", color = IdeColors.TextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            val selectedShape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(IdeColors.Selection, selectedShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Label("Projects")
            }
        }
        VerticalDivider(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().background(IdeColors.Editor)) {
            if (state.recentProjects.isEmpty()) {
                EmptyWelcome(state)
            } else {
                RecentProjects(state)
            }
        }
    }
}

@Composable
private fun EmptyWelcome(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label("Welcome to Kotlin Editor", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Label("Open an existing project folder to get started.", color = IdeColors.TextSecondary)
        Spacer(Modifier.height(24.dp))
        IdeButton(
            "Open",
            onClick = state::browseForProject,
            primary = true,
            modifier = Modifier.testTag(Tags.WELCOME_OPEN),
        )
        ErrorMessage(state)
    }
}

@Composable
private fun RecentProjects(state: AppState) {
    var query by remember { mutableStateOf("") }
    val filtered by remember(state) {
        derivedStateOf {
            state.recentProjects.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdeTextField(
                query,
                { query = it },
                Modifier.weight(1f).testTag(Tags.WELCOME_SEARCH),
                placeholder = "Search projects",
                leading = { SearchIcon() },
                onSubmit = { filtered.firstOrNull()?.let(state::openProject) },
            )
            IdeButton(
                "Open",
                onClick = state::browseForProject,
                primary = true,
                modifier = Modifier.testTag(Tags.WELCOME_OPEN),
            )
        }
        ErrorMessage(state)
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(filtered, key = { it }) { path ->
                    RecentProjectRow(
                        path,
                        onOpen = { state.openProject(path) },
                        onRemove = { state.removeRecent(path) },
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun RecentProjectRow(path: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val name = pathName(path)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) IdeColors.Hover else Color.Transparent, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onOpen)
            .testTag(ItemTags.recentProject(path))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectBadge(name, size = 28.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Label(name, fontWeight = FontWeight.SemiBold)
            Label(path, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        if (hovered) {
            IconButton(onClick = onRemove, modifier = Modifier.testTag(ItemTags.recentRemove(path))) { CloseIcon() }
        }
    }
}

@Composable
private fun ErrorMessage(state: AppState) {
    val error = state.error ?: return
    Label(error, color = IdeColors.Error, modifier = Modifier.padding(top = 8.dp).testTag(Tags.WELCOME_ERROR))
}
