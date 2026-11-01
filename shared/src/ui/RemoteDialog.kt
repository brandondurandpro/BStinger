package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.RemoteDialogState

/**
 * Remote Development: installs the server on an SSH host (if needed), starts its daemon, opens the tunnel and
 * switches the IDE to it. The catalog comes from the client's `~/.ssh/config` and the remote folders already opened.
 */
@Composable
fun RemoteDialog(app: AppState, dialog: RemoteDialogState) {
    val connect = { if (!dialog.connecting) app.connectRemote(dialog) }
    val cancel = { app.dismissRemoteDialog() }
    PopupFrame(Tags.REMOTE_DIALOG, cancel, width = 560) {
        Column {
            DialogHeader()
            RemoteCatalog(app, dialog) { app.updateRemoteDialog(it) }
            HostField(dialog, connect) { app.updateRemoteDialog(it) }
            UserAndPort(dialog, connect) { app.updateRemoteDialog(it) }
            IdentityField(dialog, connect) { app.updateRemoteDialog(it) }
            ProjectField(dialog, connect) { app.updateRemoteDialog(it) }
            Progress(app, dialog)
            DialogButtons(dialog, connect, cancel)
        }
    }
}

@Composable
private fun DialogHeader() {
    Label("Remote Development", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Label(
        "Run the server on another machine over SSH. Pick a host from ~/.ssh/config or a folder already opened. " +
            "Leave the project empty to browse the host.",
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun HostField(dialog: RemoteDialogState, onConnect: () -> Unit, onChange: (RemoteDialogState) -> Unit) {
    Field("Host or ~/.ssh/config alias") {
        IdeTextField(
            dialog.host,
            { onChange(dialog.copy(host = it, alias = "")) },
            Modifier.fillMaxWidth().testTag(Tags.REMOTE_HOST),
            placeholder = "server.example.com",
            onSubmit = onConnect,
        )
        if (dialog.alias.isNotEmpty()) {
            Label(
                "Using ~/.ssh/config: ${dialog.alias} (user, port and key come from it)",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag(Tags.REMOTE_CONFIG),
            )
        }
    }
}

@Composable
private fun UserAndPort(dialog: RemoteDialogState, onConnect: () -> Unit, onChange: (RemoteDialogState) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Field("User") {
                IdeTextField(
                    dialog.user,
                    { onChange(dialog.copy(user = it, alias = "")) },
                    Modifier.fillMaxWidth().testTag(Tags.REMOTE_USER),
                    placeholder = "current user",
                    onSubmit = onConnect,
                )
            }
        }
        Column(Modifier.width(90.dp)) {
            Field("Port") {
                IdeTextField(
                    dialog.port,
                    { onChange(dialog.copy(port = it, alias = "")) },
                    Modifier.fillMaxWidth().testTag(Tags.REMOTE_PORT),
                    placeholder = "22",
                    onSubmit = onConnect,
                )
            }
        }
    }
}

@Composable
private fun IdentityField(dialog: RemoteDialogState, onConnect: () -> Unit, onChange: (RemoteDialogState) -> Unit) {
    Field("Identity file (optional)", Modifier.padding(top = 8.dp)) {
        IdeTextField(
            dialog.identityFile,
            { onChange(dialog.copy(identityFile = it, alias = "")) },
            Modifier.fillMaxWidth().testTag(Tags.REMOTE_IDENTITY),
            placeholder = "uses ssh-agent or ~/.ssh/config when empty",
            onSubmit = onConnect,
        )
    }
}

@Composable
private fun ProjectField(dialog: RemoteDialogState, onConnect: () -> Unit, onChange: (RemoteDialogState) -> Unit) {
    Field("Project directory (optional)", Modifier.padding(top = 8.dp)) {
        IdeTextField(
            dialog.projectPath,
            { onChange(dialog.copy(projectPath = it)) },
            Modifier.fillMaxWidth().testTag(Tags.REMOTE_PROJECT),
            placeholder = "Leave empty to browse the host",
            onSubmit = onConnect,
        )
    }
}

@Composable
private fun Progress(app: AppState, dialog: RemoteDialogState) {
    app.remoteStatus?.takeIf { dialog.connecting }?.let {
        Label(
            it,
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp).testTag(Tags.REMOTE_STATUS),
        )
    }
    dialog.error?.let {
        Label(
            it,
            color = IdeColors.Error,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp).testTag(Tags.REMOTE_ERROR),
        )
    }
}

@Composable
private fun DialogButtons(dialog: RemoteDialogState, connect: () -> Unit, cancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
        IdeButton("Cancel", cancel, Modifier.testTag(Tags.REMOTE_CANCEL))
        IdeButton(
            if (dialog.connecting) "Connecting…" else "Connect",
            connect,
            Modifier.padding(start = 8.dp).testTag(Tags.REMOTE_CONNECT),
            primary = true,
            enabled = !dialog.connecting,
        )
    }
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Label(label, color = IdeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        content()
    }
}
