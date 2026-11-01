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
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.RemoteDialogState

/** Host, user, port, key and project of a host the user typed. */
@Composable
internal fun ManualForm(
    app: AppState,
    dialog: RemoteDialogState,
    onConnect: () -> Unit,
    onChange: (RemoteDialogState) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HostField(dialog, onConnect, onChange)
        UserAndPort(dialog, onConnect, onChange)
        IdentityField(dialog, onConnect, onChange)
        ProjectField(app, dialog, onConnect)
    }
}

/** The host or folder picked from the lists, with its folder and tags, ready to connect. */
@Composable
internal fun SelectedHost(app: AppState, dialog: RemoteDialogState, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Label(
            pathName(dialog.alias.ifEmpty { dialog.host }),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(RemoteTags.REMOTE_SELECTED),
        )
        if (dialog.alias.isNotEmpty()) {
            Label(
                "Using ~/.ssh/config: ${dialog.alias} (user, port and key come from it)",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp).testTag(RemoteTags.REMOTE_CONFIG),
            )
        }
        ProjectField(app, dialog, onConnect)
        TagsField(app, dialog, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun HostField(dialog: RemoteDialogState, onConnect: () -> Unit, onChange: (RemoteDialogState) -> Unit) {
    Field("Host or ~/.ssh/config alias") {
        IdeTextField(
            dialog.host,
            { onChange(dialog.copy(host = it, alias = "", selectedRow = "")) },
            Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_HOST),
            placeholder = "server.example.com",
            onSubmit = onConnect,
        )
        if (dialog.alias.isNotEmpty()) {
            Label(
                "Using ~/.ssh/config: ${dialog.alias} (user, port and key come from it)",
                color = IdeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag(RemoteTags.REMOTE_CONFIG),
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
                    Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_USER),
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
                    Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_PORT),
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
            Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_IDENTITY),
            placeholder = "uses ssh-agent or ~/.ssh/config when empty",
            onSubmit = onConnect,
        )
    }
}

@Composable
private fun ProjectField(
    app: AppState,
    dialog: RemoteDialogState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Field("Project directory (optional)", modifier.padding(top = 8.dp)) {
        IdeTextField(
            dialog.projectPath,
            { app.updateRemoteDialog(dialog.copy(projectPath = it)) },
            Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_PROJECT),
            placeholder = "Leave empty to browse the host",
            onSubmit = onConnect,
        )
    }
}

@Composable
private fun TagsField(app: AppState, dialog: RemoteDialogState, modifier: Modifier = Modifier) {
    Field("Tags (comma separated)", modifier) {
        IdeTextField(
            dialog.tags,
            { app.editRemoteTags(it) },
            Modifier.fillMaxWidth().testTag(RemoteTags.REMOTE_TAGS),
            placeholder = "production, dev",
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
