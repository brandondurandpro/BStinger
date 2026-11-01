package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.RemoteDialogState
import org.example.kotlineditor.state.RemoteWorkspace

/** SSH config hosts and already-opened remote folders, searchable from the remote dialog. */
@Suppress("CognitiveComplexMethod") // filtering, loading and two sections in one compact panel
@Composable
internal fun RemoteCatalog(app: AppState, dialog: RemoteDialogState, onChange: (RemoteDialogState) -> Unit) {
    val query = dialog.hostSearch
    val hosts = remember(app.sshHosts, query) { app.sshHosts.filter { matches(query, it.searchText) } }
    val workspaces =
        remember(app.remoteWorkspaces.toList(), query) {
            app.remoteWorkspaces.filter { matches(query, it.searchText) }
        }
    val empty = hosts.isEmpty() && workspaces.isEmpty()
    // Do not show a skeleton that changes height while ssh -G resolves the config.
    if (app.sshHostsLoading && empty) return
    if (empty && query.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        IdeTextField(
            query,
            { onChange(dialog.copy(hostSearch = it)) },
            Modifier.fillMaxWidth().testTag(Tags.REMOTE_SEARCH),
            placeholder = "Search SSH hosts and recent folders",
            leading = { SearchIcon() },
        )
        if (app.sshHostsLoading) {
            Label(
                "Loading ~/.ssh/config…",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp).testTag(Tags.REMOTE_HOSTS_LOADING),
            )
        }
        if (!app.sshHostsLoading && empty && query.isNotBlank()) {
            Label(
                "No matching host or folder",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxWidth().heightIn(max = 170.dp).testTag(Tags.REMOTE_CATALOG)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp), state = listState) {
                if (hosts.isNotEmpty()) {
                    item { SectionHeader("~/.ssh/config") }
                    items(hosts, key = { "h:${it.alias}" }) { host ->
                        SshHostRow(host) { app.selectSshHost(host) }
                    }
                }
                if (workspaces.isNotEmpty()) {
                    item { SectionHeader("Recent folders") }
                    items(workspaces, key = { "w:${it.key}" }) { workspace ->
                        WorkspaceRow(workspace) { app.selectRemoteWorkspace(workspace) }
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
private fun SectionHeader(text: String) {
    Label(
        text,
        color = IdeColors.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SshHostRow(host: SshConfigHost, onClick: () -> Unit) {
    CatalogRow(ItemTags.sshHost(host.alias), onClick) {
        Column {
            Label(host.alias, fontWeight = FontWeight.SemiBold)
            Label(host.summary, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WorkspaceRow(workspace: RemoteWorkspace, onClick: () -> Unit) {
    CatalogRow(ItemTags.remoteWorkspace(workspace.label, workspace.path), onClick) {
        Column {
            Label(workspace.path, fontSize = 12.sp)
            Label(workspace.label, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CatalogRow(tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) IdeColors.Hover else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

private fun matches(query: String, text: String): Boolean =
    query.isBlank() || text.contains(query.trim(), ignoreCase = true)
