package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
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
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.state.DeployStatus
import org.example.kotlineditor.state.DeploymentsState
import org.example.kotlineditor.state.duplicate
import org.example.kotlineditor.state.edit
import org.example.kotlineditor.state.landingOf

private val LIST_MAX_HEIGHT = 420.dp
private val TRIGGER_WIDTH = 110.dp
private val ACTIONS_WIDTH = 120.dp
private val MENU_WIDTH = 140.dp
private const val NAME_WEIGHT = 1.1f
private const val TARGET_WEIGHT = 1.5f
private const val FILES_WEIGHT = 1.4f
private const val WHOLE_PROJECT = "(whole project)"

/** The saved deployments in a table (name, trigger, target, files, actions), then a line on where they go. */
@Composable
internal fun DeploymentList(deployments: DeploymentsState) {
    val profiles = deployments.profiles
    TableFrame(
        header = {
            ColumnTitle("Name", Modifier.weight(NAME_WEIGHT))
            ColumnTitle("Trigger", Modifier.width(TRIGGER_WIDTH))
            ColumnTitle("Target", Modifier.weight(TARGET_WEIGHT))
            ColumnTitle("Files", Modifier.weight(FILES_WEIGHT))
            Box(Modifier.width(ACTIONS_WIDTH))
        },
    ) {
        if (profiles.isEmpty() && !deployments.loading) {
            Label(
                "No deployment yet. Create one, or use Deploy to… in the project view, then Save as deployment.",
                color = IdeColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                modifier = Modifier.padding(16.dp).testTag(AppTags.DEPLOYMENTS_EMPTY),
            )
        }
        Column(Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            for (profile in profiles) {
                RowDivider()
                DeploymentRow(deployments, profile)
            }
        }
    }
    if (profiles.isNotEmpty()) {
        Label(
            "${kindsSummary(profiles)} Hover a file name to see its full path.",
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp).testTag(AppTags.DEPLOYMENTS_SUMMARY),
        )
    }
}

// Where the deployments go, said once for all: "All targets are Local.", or how many go over SSH.
internal fun kindsSummary(profiles: List<DeployProfile>): String {
    val remote = profiles.count { DeployDestination.parse(it.destination)?.isRemote == true }
    val local = profiles.size - remote
    return when {
        remote == 0 -> "All targets are Local."
        local == 0 -> "All targets are reached over SSH."
        else -> "$local local, $remote over SSH."
    }
}

@Composable
private fun DeploymentRow(deployments: DeploymentsState, profile: DeployProfile) {
    val status = deployments.statuses[profile.name]
    Column(
        Modifier.fillMaxWidth().testTag(AppTags.deployment(profile.name)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(profile.name, Modifier.weight(NAME_WEIGHT), fontWeight = FontWeight.SemiBold)
            Box(Modifier.width(TRIGGER_WIDTH)) { TriggerBadge(profile.auto) }
            TargetCell(profile, Modifier.weight(TARGET_WEIGHT))
            FilesCell(profile, Modifier.weight(FILES_WEIGHT))
            Row(Modifier.width(ACTIONS_WIDTH), horizontalArrangement = Arrangement.End) {
                RowActions(deployments, profile, status == DeployStatus.Running)
            }
        }
        StatusLine(profile.name, status)
    }
}

/** "Manual" or "Automatic", with its dot. */
@Composable
internal fun TriggerBadge(auto: Boolean) {
    if (auto) DotBadge("Automatic", IdeColors.Accent) else DotBadge("Manual", IdeColors.TextSecondary)
}

// The destination as typed; one reached over SSH says so first.
@Composable
private fun TargetCell(profile: DeployProfile, modifier: Modifier) {
    val destination = DeployDestination.parse(profile.destination)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (destination?.isRemote == true) {
            DotBadge(
                "SSH",
                IdeColors.Accent,
                Modifier.padding(end = 6.dp).testTag(AppTags.deploymentKind(profile.name)),
            )
        }
        MonoLabel(profile.destination)
    }
}

// The names of what is sent, one per line; the pointer over them shows each full path and where it lands.
@Composable
private fun FilesCell(profile: DeployProfile, modifier: Modifier) {
    val folders = profile.folders.ifEmpty { listOf(DeployFolder("", "")) }
    val paths =
        folders.joinToString("\n") {
            val landing = landingOf(profile.destination, it.target).orEmpty()
            "${it.source.ifEmpty { WHOLE_PROJECT }}  →  $landing"
        }
    HoverHint(paths, modifier) {
        Column(
            Modifier.testTag(AppTags.deploymentFiles(profile.name)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            folders.forEachIndexed { index, folder ->
                val name = folder.source.substringAfterLast('/').ifEmpty { WHOLE_PROJECT }
                MonoLabel(name, Modifier.testTag(AppTags.deploymentFile(profile.name, index)))
            }
        }
    }
}

// Deploy, then the other actions behind "⋯".
@Composable
private fun RowActions(deployments: DeploymentsState, profile: DeployProfile, running: Boolean) {
    DeployButton(
        if (running) "Deploying…" else "Deploy",
        { deployments.run(profile) },
        Modifier.testTag(AppTags.deploymentRun(profile.name)),
        enabled = !running,
    )
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 4.dp)) {
        DeployButton(
            "⋯",
            { open = true },
            Modifier.testTag(AppTags.deploymentMenu(profile.name)),
            DeployButtonVariant.Ghost,
        )
        DropdownMenu(open, { open = false }) {
            MenuItem("Edit", AppTags.deploymentEdit(profile.name)) {
                open = false
                deployments.edit(profile)
            }
            MenuItem("Duplicate", AppTags.deploymentDuplicate(profile.name)) {
                open = false
                deployments.duplicate(profile)
            }
            MenuItem("Delete", AppTags.deploymentDelete(profile.name), IdeColors.Error) {
                open = false
                deployments.delete(profile)
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, color: Color = IdeColors.Text, onClick: () -> Unit) {
    Box(
        Modifier
            .width(MENU_WIDTH)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        Label(text, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun StatusLine(name: String, status: DeployStatus?) {
    val (text, color) =
        when (status) {
            is DeployStatus.Done -> status.text to IdeColors.TextSecondary
            is DeployStatus.Failed -> status.text to IdeColors.Error
            else -> return
        }
    Label(
        text,
        color = color,
        fontSize = 12.sp,
        maxLines = 3,
        modifier = Modifier.padding(top = 6.dp).testTag(AppTags.deploymentStatus(name)),
    )
}
