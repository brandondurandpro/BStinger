package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.DeploymentsState
import org.example.kotlineditor.state.startNew

private const val POPUP_WIDTH = 780

/** The deployments saved for the project: which folders go where. Lists them, runs them, creates and edits them. */
@Composable
fun DeploymentsPopup(deployments: DeploymentsState) {
    // A click beside the popup closes the list, but not a form: what was typed in it would be lost.
    val closeOnBackdrop = { if (deployments.draft == null) deployments.close() }
    PopupFrame(DeployTags.DEPLOYMENTS, closeOnBackdrop, width = POPUP_WIDTH) {
        Column(Modifier.padding(6.dp)) {
            val draft = deployments.draft
            if (draft == null) {
                ListHeader(deployments)
                DeploymentList(deployments)
            } else {
                val title = if (draft.original == null) "New deployment" else "Edit deployment"
                Heading(title, "Where the files go, then which folders of the project.")
                DeploymentForm(deployments, draft)
            }
            deployments.error?.let {
                Label(
                    it,
                    color = IdeColors.Error,
                    fontSize = 12.sp,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 8.dp).testTag(DeployTags.DEPLOYMENTS_ERROR),
                )
            }
        }
    }
}

// The title, how many deployments there are, the button that adds one, and the one that closes.
@Composable
private fun ListHeader(deployments: DeploymentsState) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("Deployments", plural(deployments.profiles.size, "target", "targets"))
        }
        DeployButton(
            "+  New deployment",
            deployments::startNew,
            Modifier.testTag(DeployTags.DEPLOYMENTS_NEW),
            variant = DeployButtonVariant.Primary,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(deployments::close, Modifier.size(28.dp).testTag(DeployTags.DEPLOYMENTS_CLOSE)) {
            CloseIcon(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Label(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Label(subtitle, color = IdeColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
