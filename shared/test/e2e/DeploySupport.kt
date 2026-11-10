package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import java.nio.file.Path

private const val RESULT_TIMEOUT = 60_000L

/** Whether the disk of the tests is a Windows one (no POSIX shell to stand in for the host of a deployment). */
val WINDOWS_DISK = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Opens Deploy to… on the tree row of [path] and waits for the dialog. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openDeployDialog(path: String) {
    rightClickOn(TreeTags.treeRow(path))
    awaitTag(TreeTags.MENU_DEPLOY).performClick()
    awaitTag(AppTags.FILE_DIALOG_INPUT)
}

/** Types [destination] in the open Deploy dialog and presses Deploy (does not wait for the result). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.submitDeploy(destination: String) {
    onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextReplacement(destination)
    onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
}

/** Deploys the tree row [path] to [destination] and returns what the dialog says once done. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.deployFromTree(path: String, destination: String): String {
    openDeployDialog(path)
    submitDeploy(destination)
    return awaitDeployResult()
}

/** The result line of the Deploy dialog, once it shows. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitDeployResult(): String {
    val config = awaitTag(AppTags.FILE_DIALOG_RESULT, timeout = RESULT_TIMEOUT).fetchSemanticsNode().config
    val lines = config.getOrElseNullable(SemanticsProperties.Text) { null }
    return lines?.joinToString("") { it.text }.orEmpty()
}

/**
 * A stand-in for `ssh` that runs the command of the host on this machine, in [home] (the home of the SSH user), and
 * writes the host it was given to `host.txt` in [directory]; `null` where there is no POSIX shell. With [failure],
 * it says that instead and exits like a refused connection.
 */
fun fakeSsh(directory: Path, home: Path, failure: String? = null): List<String>? {
    if (WINDOWS_DISK) return null
    val script = directory.resolve("fake-ssh.sh")
    val run = if (failure == null) "cd '$home' && exec sh -c \"\$1\"" else "echo '$failure' >&2; exit 255"
    val text =
        """
        #!/bin/sh
        while [ "${'$'}1" != "--" ]; do shift; done
        shift
        echo "${'$'}1" > '${directory.resolve("host.txt")}'
        shift
        $run
        """.trimIndent() + "\n"
    Files.writeString(script, text)
    script.toFile().setExecutable(true)
    return listOf(script.toString())
}

/** Opens the popup of the saved deployments from the toolbar. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openDeployments() {
    onNodeWithTag(AppTags.DEPLOY_BUTTON).performClick()
    awaitTag(AppTags.DEPLOYMENTS)
}

/** Closes the popup of the saved deployments. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.closeDeployments() {
    onNodeWithTag(AppTags.DEPLOYMENTS_CLOSE).performClick()
    awaitNoTag(AppTags.DEPLOYMENTS)
}

/**
 * Fills the form of a deployment, destination first as the form wants, then one field per folder of [sources]
 * (separated by commas, `source=target` to choose where it lands; none for the whole project), and presses Save
 * (does not wait).
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.fillDeployment(name: String, sources: String, destination: String, auto: Boolean = false) {
    awaitTag(AppTags.DEPLOYMENT_NAME).performTextReplacement(name)
    onNodeWithTag(AppTags.DEPLOYMENT_DESTINATION).performTextReplacement(destination)
    val folders = sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // A destination that is no destination hides the folders: nothing to fill.
    if (DeployDestination.parse(destination) != null) fillFolders(folders)
    if (auto) onNodeWithTag(AppTags.DEPLOYMENT_MODE).performClick()
    onNodeWithTag(AppTags.DEPLOYMENT_SAVE).performClick()
}

// Makes the folder fields hold exactly [folders] (one empty field when there is none).
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.fillFolders(folders: List<String>) {
    val wanted = folders.ifEmpty { listOf("") }
    awaitTag(AppTags.deploymentSource(0))
    while (hasFolderField(wanted.size)) onNodeWithTag(AppTags.deploymentRemoveFolder(wanted.size)).performClick()
    wanted.forEachIndexed { index, folder ->
        // `source=target` gives the folder its own path on the destination.
        val source = folder.substringBefore('=')
        val target = if ('=' in folder) folder.substringAfter('=') else null
        if (!hasFolderField(index)) onNodeWithTag(AppTags.DEPLOYMENT_ADD_FOLDER).performClick()
        awaitTag(AppTags.deploymentSource(index)).performTextReplacement(source)
        if (target != null) onNodeWithTag(AppTags.deploymentTarget(index)).performTextReplacement(target)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.hasFolderField(index: Int): Boolean =
    onAllNodesWithTag(AppTags.deploymentSource(index)).fetchSemanticsNodes().isNotEmpty()

/** Creates a deployment from the open popup and waits until it is listed. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.createDeployment(name: String, sources: String, destination: String, auto: Boolean = false) {
    awaitTag(AppTags.DEPLOYMENTS_NEW).performClick()
    fillDeployment(name, sources, destination, auto)
    awaitTag(AppTags.deployment(name))
}

/** The text of the status line of the deployment [name], once it shows one. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitDeploymentStatus(name: String): String {
    val config = awaitTag(AppTags.deploymentStatus(name)).fetchSemanticsNode().config
    val lines = config.getOrElseNullable(SemanticsProperties.Text) { null }
    return lines?.joinToString("") { it.text }.orEmpty()
}

/** Whether the file [path] exists and holds exactly [text]. */
fun hasText(path: Path, text: String): Boolean {
    if (!Files.exists(path)) return false
    // On Windows the file may be locked while the deployer is writing it: not there yet.
    return try {
        Files.readString(path) == text
    } catch (_: java.io.IOException) {
        false
    }
}

/** Opens the menu of the row of the deployment [name] (Edit, Duplicate, Delete). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openDeploymentMenu(name: String) {
    awaitTag(AppTags.deploymentMenu(name)).performClick()
    awaitTag(AppTags.deploymentEdit(name))
}

/** Whether the file at [path] starts with [prefix]; false while it is missing or locked by the deployer (Windows). */
fun hasStartingText(path: Path, prefix: String): Boolean {
    if (!Files.exists(path)) return false
    return try {
        Files.readString(path).startsWith(prefix)
    } catch (_: java.io.IOException) {
        false
    }
}
