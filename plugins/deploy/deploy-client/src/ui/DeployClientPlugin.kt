package org.example.kotlineditor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.DialogContribution
import org.example.kotlineditor.plugin.ToolbarContribution
import org.example.kotlineditor.state.DEPLOY_SERVICE
import org.example.kotlineditor.state.ProjectHost

/**
 * The client half of the bundled Deploy feature: its button in the toolbar, and the popup of the saved
 * deployments shown over the project while it is open.
 */
class DeployClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolbarButton(ToolbarContribution(rank = 1) { project -> DeployToolbarButton(project) })
        contributions.dialog(
            DialogContribution { project ->
                val deployments = project.service(DEPLOY_SERVICE)
                if (deployments.open) DeploymentsPopup(deployments)
            },
        )
    }
}

/** The Deploy button of the toolbar: opens the popup of the saved deployments. */
@Composable
fun DeployToolbarButton(project: ProjectHost, modifier: Modifier = Modifier) {
    IdeButton("Deploy", { project.service(DEPLOY_SERVICE).show() }, modifier.testTag(DeployTags.BUTTON))
}
