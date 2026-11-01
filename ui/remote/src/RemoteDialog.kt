package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.RemoteDialogState

/**
 * Remote Development: installs the server on an SSH host (if needed), starts its daemon, opens the tunnel and
 * switches the IDE to it. Pick view by default (folders, `~/.ssh/config` hosts and tags); a "New host…" button
 * shows the manual form.
 */
@Composable
fun RemoteDialog(app: AppState, dialog: RemoteDialogState) {
    val connect = { if (!dialog.connecting) app.connectRemote(dialog) }
    val cancel = { app.dismissRemoteDialog() }
    PopupFrame(RemoteTags.REMOTE_DIALOG, cancel, width = 760) {
        Column {
            DialogHeader()
            RemoteCatalog(app, dialog) { app.updateRemoteDialog(it) }
            if (dialog.manual) {
                ManualForm(app, dialog, connect) { app.updateRemoteDialog(it) }
            } else if (dialog.host.isNotBlank()) {
                SelectedHost(app, dialog, connect)
            }
            Progress(app, dialog)
            DialogButtons(app, dialog, connect, cancel)
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
private fun Progress(app: AppState, dialog: RemoteDialogState) {
    app.remoteStatus?.takeIf { dialog.connecting }?.let {
        Label(
            it,
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp).testTag(RemoteTags.REMOTE_STATUS),
        )
    }
    dialog.error?.let {
        Label(
            it,
            color = IdeColors.Error,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp).testTag(RemoteTags.REMOTE_ERROR),
        )
    }
}

@Composable
private fun DialogButtons(app: AppState, dialog: RemoteDialogState, connect: () -> Unit, cancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeButton(
            if (dialog.manual) "Choose from list…" else "New host…",
            {
                if (dialog.manual) {
                    app.chooseRemoteHost()
                } else {
                    app.addRemoteHost()
                }
            },
            Modifier.testTag(RemoteTags.REMOTE_NEW_HOST),
        )
        Spacer(Modifier.weight(1f))
        IdeButton("Cancel", cancel, Modifier.testTag(RemoteTags.REMOTE_CANCEL))
        IdeButton(
            if (dialog.connecting) "Connecting…" else "Connect",
            connect,
            Modifier
                .padding(start = 8.dp)
                .testTag(RemoteTags.REMOTE_CONNECT),
            primary = true,
            enabled = !dialog.connecting,
        )
    }
}
