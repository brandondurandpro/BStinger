package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.state.DeployDraft
import org.example.kotlineditor.state.DeploymentsState
import org.example.kotlineditor.state.DraftFolder
import org.example.kotlineditor.state.addFolder
import org.example.kotlineditor.state.addSelected
import org.example.kotlineditor.state.effectiveTarget
import org.example.kotlineditor.state.foldersOf
import org.example.kotlineditor.state.landingOf
import org.example.kotlineditor.state.normalizeSource
import org.example.kotlineditor.state.removeFolder
import org.example.kotlineditor.state.saveDraft
import org.example.kotlineditor.state.setSource
import org.example.kotlineditor.state.setTarget

private val FOLDERS_MAX_HEIGHT = 260.dp
private val REMOVE_WIDTH = 28.dp

/** The form of a deployment: its name, its destination, its folders (each with the path it lands at), its trigger. */
@Composable
internal fun DeploymentForm(deployments: DeploymentsState, draft: DeployDraft) {
    // The fields scroll in what the window leaves; Cancel and Save stay in sight under them.
    Column {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FieldHeading("Name")
            IdeTextField(
                draft.name,
                { deployments.draft = draft.copy(name = it, error = null) },
                Modifier.fillMaxWidth().testTag(DeployTags.DEPLOYMENT_NAME),
                placeholder = "e.g. Production site",
            )
            // The destination comes first: each folder then shows where it will land.
            DestinationPicker(deployments, draft)
            FieldHeading("Folders", "What to send, and the path each takes under the destination.")
            if (DeployDestination.parse(draft.destination) == null) {
                Label(
                    "Enter the destination first: each folder then shows where it will land.",
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(DeployTags.DEPLOYMENT_FOLDERS_HINT),
                )
            } else {
                FolderTable(deployments, draft)
            }
            TriggerChoice(deployments, draft)
            draft.error?.let {
                Label(
                    it,
                    color = IdeColors.Error,
                    fontSize = 12.sp,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 10.dp).testTag(DeployTags.DEPLOYMENT_DRAFT_ERROR),
                )
            }
        }
        FormButtons(deployments)
    }
}

@Composable
private fun FormButtons(deployments: DeploymentsState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        DeployButton("Cancel", { deployments.draft = null }, Modifier.testTag(DeployTags.DEPLOYMENT_CANCEL))
        DeployButton(
            "Save",
            deployments::saveDraft,
            Modifier.testTag(DeployTags.DEPLOYMENT_SAVE),
            variant = DeployButtonVariant.Primary,
        )
    }
}

// Manual or Automatic, and what the chosen one means.
@Composable
private fun TriggerChoice(deployments: DeploymentsState, draft: DeployDraft) {
    FieldHeading("Trigger")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Segmented(
            listOf(
                Segment("Manual", DeployTags.DEPLOYMENT_MODE_MANUAL, !draft.auto) {
                    deployments.draft = draft.copy(auto = false, error = null)
                },
                Segment("Automatic", DeployTags.DEPLOYMENT_MODE, draft.auto) {
                    deployments.draft = draft.copy(auto = true, error = null)
                },
            ),
        )
        val meaning =
            if (draft.auto) "Sent as soon as a file of these folders changes." else "Sent when you press Deploy."
        Label(meaning, color = IdeColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

// One row per folder: where it is in the project, where it lands on the destination (its own path until another one
// is typed), and under them the full place it ends up at.
@Composable
private fun FolderTable(deployments: DeploymentsState, draft: DeployDraft) {
    TableFrame(
        header = {
            ColumnTitle("In the project", Modifier.weight(1f))
            ColumnTitle("On the destination", Modifier.weight(1f))
            Box(Modifier.width(REMOVE_WIDTH))
        },
    ) {
        Column(Modifier.heightIn(max = FOLDERS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            draft.folders.forEachIndexed { index, row ->
                RowDivider()
                FolderRow(deployments, draft, index, row)
            }
        }
        RowDivider()
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DeployButton(
                "+  Add folder",
                deployments::addFolder,
                Modifier.testTag(DeployTags.DEPLOYMENT_ADD_FOLDER),
                DeployButtonVariant.Ghost,
            )
            DeployButton(
                "Add selected in project view",
                deployments::addSelected,
                Modifier.testTag(DeployTags.DEPLOYMENT_ADD_SELECTED),
                DeployButtonVariant.Ghost,
            )
        }
    }
    if (foldersOf(draft.folders).orEmpty().isEmpty()) {
        val whole = landingOf(draft.destination, "").orEmpty()
        Label(
            "No folder: the whole project goes to $whole",
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun FolderRow(deployments: DeploymentsState, draft: DeployDraft, index: Int, row: DraftFolder) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IdeTextField(
                row.source,
                { deployments.setSource(index, it) },
                Modifier.weight(1f).testTag(DeployTags.deploymentSource(index)),
                placeholder = "src or docs/api (empty: whole project)",
                monospace = true,
            )
            TargetField(deployments, index, row, Modifier.weight(1f))
            Box(Modifier.width(REMOVE_WIDTH), contentAlignment = Alignment.Center) {
                IconButton(
                    { deployments.removeFolder(index) },
                    Modifier.testTag(DeployTags.deploymentRemoveFolder(index)),
                ) {
                    CloseIcon(Modifier.size(10.dp))
                }
            }
        }
        val landing = landingOf(draft.destination, row.effectiveTarget().orEmpty())
        val invalid = landing == null || row.effectiveTarget() == null || normalizeSource(row.source) == null
        val text = if (invalid) "Not a path inside the project and the destination" else "→  $landing"
        MonoLabel(
            text,
            Modifier.padding(top = 6.dp).testTag(DeployTags.deploymentLanding(index)),
            color = if (invalid) IdeColors.Error else IdeColors.TextSecondary,
        )
    }
}

// The path under the destination; once changed, a "↺" in the field brings back the folder's own path.
@Composable
private fun TargetField(deployments: DeploymentsState, index: Int, row: DraftFolder, modifier: Modifier) {
    val own = row.target == null
    val shown = row.target ?: normalizeSource(row.source) ?: row.source
    IdeTextField(
        shown,
        { deployments.setTarget(index, it) },
        modifier.testTag(DeployTags.deploymentTarget(index)),
        placeholder = "empty: the destination itself",
        monospace = true,
        trailing =
        if (own) {
            null
        } else {
            {
                HoverHint("Same path as in the project") {
                    Label(
                        "↺",
                        color = IdeColors.TextSecondary,
                        modifier =
                        Modifier
                            .clickable { deployments.setTarget(index, null) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag(DeployTags.deploymentTargetReset(index)),
                    )
                }
            }
        },
    )
}
