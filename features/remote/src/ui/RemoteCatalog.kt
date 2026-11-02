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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.state.RemoteDialogState
import org.example.kotlineditor.state.RemoteHost
import org.example.kotlineditor.state.RemoteWorkspace

/**
 * Recent remote folders and `~/.ssh/config` hosts, searchable; the picked line is highlighted like an IntelliJ
 * selection, rows carry the user's tags as chips, and clicking a chip filters by it.
 */
@Suppress("CognitiveComplexMethod") // filtering, loading and two sections in one compact panel
@Composable
internal fun RemoteCatalog(app: RemoteHost, dialog: RemoteDialogState, onChange: (RemoteDialogState) -> Unit) {
    val query = dialog.hostSearch
    val workspaces =
        app.remoteWorkspaces
            .filter { matches(query, it.searchText + " " + app.tagsFor(it.target.tagKey).joinToString(" ")) }
            .map { it to (dialog.selectedRow == "w:${it.key}") }
    val hosts =
        app.sshHosts
            .filter { matches(query, it.searchText + " " + app.tagsFor(it.alias).joinToString(" ")) }
            .map { it to (dialog.selectedRow == "h:${it.alias}") }
    val empty = hosts.isEmpty() && workspaces.isEmpty()
    // Do not show a skeleton that changes height while ssh -G resolves the config.
    if (app.sshHostsLoading && empty) return
    if (empty && query.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        IdeTextField(
            query,
            { onChange(dialog.copy(hostSearch = it)) },
            Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_SEARCH),
            placeholder = "Search recent folders, SSH hosts and tags",
            leading = { SearchIcon() },
        )
        CatalogStatus(app, empty, query)
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag(RemoteTags.REMOTE_CATALOG)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = listState) {
                if (workspaces.isNotEmpty()) {
                    item { SectionHeader("Recent folders") }
                    items(workspaces, key = { "w:${it.first.key}" }) { (workspace, isSelected) ->
                        WorkspaceRow(app, workspace, isSelected, onTagClick = { tag ->
                            onChange(dialog.copy(hostSearch = tag))
                        }) { app.selectRemoteWorkspace(workspace) }
                    }
                }
                if (hosts.isNotEmpty()) {
                    item { SectionHeader("~/.ssh/config") }
                    items(hosts, key = { "h:${it.first.alias}" }) { (host, isSelected) ->
                        SshHostRow(
                            app.tagsFor(host.alias),
                            host,
                            isSelected,
                            onTagClick = { tag -> onChange(dialog.copy(hostSearch = tag)) },
                        ) { app.selectSshHost(host) }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CatalogStatus(app: RemoteHost, empty: Boolean, query: String) {
    if (app.sshHostsLoading) {
        Label(
            "Loading ~/.ssh/config…",
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp).testTag(RemoteTags.REMOTE_HOSTS_LOADING),
        )
    }
    val noMatch = empty && query.isNotBlank()
    if (!app.sshHostsLoading && noMatch) {
        Label(
            "No matching host or folder",
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Label(
        text,
        color = IdeColors.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SshHostRow(
    tags: List<String>,
    host: SshConfigHost,
    isSelected: Boolean,
    onTagClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    CatalogRow(RemoteTags.sshHost(host.alias), isSelected, onClick) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(host.alias, fontWeight = FontWeight.SemiBold)
                tags.forEach { tag -> TagChip(tag, RemoteTags.remoteTag(host.alias, tag), onTagClick) }
            }
            if (host.summary.isNotBlank()) {
                Label(host.summary, color = IdeColors.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    app: RemoteHost,
    workspace: RemoteWorkspace,
    isSelected: Boolean,
    onTagClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    val tags = app.tagsFor(workspace.target.tagKey)
    CatalogRow(RemoteTags.remoteWorkspace(workspace.label, workspace.path), isSelected, onClick) {
        Column {
            Label(workspace.path, fontSize = 12.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(workspace.label, color = IdeColors.TextSecondary, fontSize = 11.sp)
                tags.forEach { tag -> TagChip(tag, RemoteTags.remoteTag(workspace.target.tagKey, tag), onTagClick) }
            }
        }
    }
}

@Composable
private fun TagChip(tag: String, tagForTest: String, onClick: (String) -> Unit) {
    val color = tagColor(tag)
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .testTag(tagForTest)
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(tag) },
            ),
    ) {
        Label(tag, color = color, fontSize = 10.sp)
    }
}

private fun tagColor(tag: String): Color =
    when (tag.lowercase()) {
        "prod", "production" -> IdeColors.Error
        "staging", "stg" -> IdeColors.GitModified
        "test", "qa" -> IdeColors.GitUntracked
        "dev", "development" -> IdeColors.GitAdded
        else -> IdeColors.TextSecondary
    }

@Composable
private fun CatalogRow(tag: String, isSelected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rowBackground =
        when {
            isSelected -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBackground, RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

private fun matches(query: String, text: String): Boolean =
    query.isBlank() || text.contains(query.trim(), ignoreCase = true)
