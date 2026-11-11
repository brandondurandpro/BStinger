package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.state.DeployDraft
import org.example.kotlineditor.state.DeploymentsState
import org.example.kotlineditor.state.setRemote
import org.example.kotlineditor.state.setSshFolder
import org.example.kotlineditor.state.setSshHost
import org.example.kotlineditor.state.sshFolderOf
import org.example.kotlineditor.state.sshHostOf

private val SELECT_SHAPE = RoundedCornerShape(6.dp)
private val HOSTS_MAX_HEIGHT = 240.dp
private val MENU_WIDTH = 660.dp
private val NAME_COLUMN_WIDTH = 300.dp

/**
 * Where the deployment goes: a choice between a folder of the disk and a machine reached over SSH. For SSH, the
 * machines of the SSH configuration of the server are listed (a host can also be typed), then the folder on it.
 */
@Composable
internal fun DestinationPicker(deployments: DeploymentsState, draft: DeployDraft) {
    FieldHeading("Destination", "A folder of this machine, or a folder of a server reached over SSH.") {
        DestinationBadge(DeployDestination.parse(draft.destination), DeployTags.DEPLOYMENT_KIND)
    }
    Segmented(
        listOf(
            Segment("Local folder", DeployTags.DEPLOYMENT_KIND_LOCAL, !draft.remote) { deployments.setRemote(false) },
            Segment("SSH server", DeployTags.DEPLOYMENT_KIND_SSH, draft.remote) { deployments.setRemote(true) },
        ),
    )
    if (draft.remote) SshDestination(deployments, draft) else LocalDestination(deployments, draft)
}

@Composable
private fun LocalDestination(deployments: DeploymentsState, draft: DeployDraft) {
    IdeTextField(
        draft.destination,
        { deployments.draft = draft.copy(destination = it, error = null) },
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag(DeployTags.DEPLOYMENT_DESTINATION),
        placeholder = "/var/www",
        monospace = true,
    )
}

@Composable
private fun SshDestination(deployments: DeploymentsState, draft: DeployDraft) {
    val host = sshHostOf(draft.destination)
    val hosts = deployments.sshHosts
    if (hosts.isNotEmpty()) {
        SshServerPicker(hosts, host) { deployments.setSshHost(it.alias) }
    }
    IdeTextField(
        host,
        { deployments.setSshHost(it) },
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag(DeployTags.DEPLOYMENT_SSH_HOST),
        placeholder = if (hosts.isEmpty()) "user@host" else "Or type another server, e.g. user@host",
    )
    if (host.isNotEmpty()) {
        IdeTextField(
            sshFolderOf(draft.destination),
            { deployments.setSshFolder(it) },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag(DeployTags.DEPLOYMENT_DESTINATION),
            placeholder = "Folder on the server, e.g. /var/www (empty: its home folder)",
            monospace = true,
        )
    }
}

// The servers of the SSH configuration in a menu: a search field, then one line per server in two columns (its name,
// where it is). The menu closes when a server is picked, or on a click or Escape elsewhere.
@Composable
private fun SshServerPicker(hosts: List<SshConfigHost>, selected: String, onPick: (SshConfigHost) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val current = hosts.firstOrNull { it.alias == selected }
    val shown =
        if (current ==
            null
        ) {
            "Choose a server from your SSH config…"
        } else {
            "${current.alias}   ${current.summary}"
        }
    Box(Modifier.padding(top = 8.dp)) {
        SelectBox(shown, current != null, Modifier.testTag(DeployTags.DEPLOYMENT_SSH_PICKER)) {
            query = ""
            open = true
        }
        DropdownMenu(open, { open = false }, Modifier.width(MENU_WIDTH)) {
            SshServerList(hosts, query, { query = it }) {
                open = false
                onPick(it)
            }
        }
    }
}

@Composable
private fun SshServerList(
    hosts: List<SshConfigHost>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (SshConfigHost) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    IdeTextField(
        query,
        onQuery,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .focusRequester(focus)
            .testTag(DeployTags.DEPLOYMENT_SSH_SEARCH),
        placeholder = "Search a server",
    )
    val matching = hosts.filter { it.searchText.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.heightIn(max = HOSTS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        for (host in matching) SshHostRow(host, onPick)
        if (matching.isEmpty()) {
            Label(
                "No server matches",
                color = IdeColors.TextSecondary,
                modifier = Modifier.padding(8.dp).testTag(DeployTags.DEPLOYMENT_SSH_NONE),
            )
        }
    }
}

// The name of the server and where it is, each in its own column, the same width on every line.
@Composable
private fun SshHostRow(host: SshConfigHost, onPick: (SshConfigHost) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(host) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(DeployTags.deploymentSshHost(host.alias)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(host.alias, Modifier.width(NAME_COLUMN_WIDTH).padding(end = 12.dp))
        Label(host.summary, Modifier.weight(1f), color = IdeColors.TextSecondary)
    }
}

/** "Local" for a folder of the server's disk, "Remote · host" for a folder of another machine reached over SSH. */
@Composable
internal fun DestinationBadge(destination: DeployDestination?, tag: String) {
    if (destination == null) return
    val remote = destination.isRemote
    val text = if (remote) "Remote · ${destination.host}" else "Local"
    val color = if (remote) IdeColors.Accent else IdeColors.TextSecondary
    DotBadge(text, color, Modifier.padding(start = 8.dp).testTag(tag))
}

// A field that opens a list: the value chosen (or what to do), and a chevron.
@Composable
private fun SelectBox(text: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(IdeColors.Input, SELECT_SHAPE)
            .border(1.dp, IdeColors.InputBorder, SELECT_SHAPE)
            .clickable(onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, Modifier.weight(1f), color = if (chosen) IdeColors.Text else IdeColors.TextSecondary)
        Chevron(expanded = true)
    }
}
