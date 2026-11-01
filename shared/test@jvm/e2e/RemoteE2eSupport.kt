package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.Tags

/** Runs [block] in a Compose test, or does nothing on a platform that cannot host the POSIX test remote. */
@OptIn(ExperimentalTestApi::class)
fun remoteUiTest(block: ComposeUiTest.() -> Unit) {
    if (!RemoteTestEnvironment.available) return
    runComposeUiTest { block() }
}

/** Replaces the folder-picker path in one edit (one debounce). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.replaceFolderPath(text: String) {
    onNodeWithTag(Tags.FOLDER_PICKER_PATH).performClick()
    onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextClearance()
    if (text.isNotEmpty()) onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextInput(text)
}

/** Replaces the folder-picker path with several quick edits, as a user typing would produce. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.typeFolderPathInChunks(vararg chunks: String) {
    onNodeWithTag(Tags.FOLDER_PICKER_PATH).performClick()
    onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextClearance()
    chunks.forEach { onNodeWithTag(Tags.FOLDER_PICKER_PATH).performTextInput(it) }
}

/** Fills the Remote Development dialog for the fake SSH server of [RemoteTestEnvironment]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.connectRemoteTo(
    environment: RemoteTestEnvironment,
    projectPath: String?,
    host: String = "127.0.0.1",
    user: String = "tester",
    identity: String = environment.ssh.identity.toString(),
) {
    onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
    awaitTag(Tags.REMOTE_DIALOG)
    // The pick view hides the manual form; adding a host happens through its button.
    onNodeWithTag(Tags.REMOTE_NEW_HOST).performClick()
    awaitTag(Tags.REMOTE_HOST)
    if (host.isNotEmpty()) {
        onNodeWithTag(Tags.REMOTE_HOST).performTextInput(host)
    }
    if (user.isNotEmpty()) {
        onNodeWithTag(Tags.REMOTE_USER).performTextInput(user)
    }
    onNodeWithTag(Tags.REMOTE_PORT).performTextClearance()
    onNodeWithTag(Tags.REMOTE_PORT).performTextInput(environment.ssh.port.toString())
    if (identity.isNotEmpty()) {
        onNodeWithTag(Tags.REMOTE_IDENTITY).performTextInput(identity)
    }
    if (projectPath != null) {
        onNodeWithTag(Tags.REMOTE_PROJECT).performTextInput(projectPath)
    }
    onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
}
