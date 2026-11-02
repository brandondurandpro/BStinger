package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.RemotePreferenceKeys
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The remote dialog's catalog: hosts from ~/.ssh/config and remote folders already opened. */
@OptIn(ExperimentalTestApi::class)
class RemoteSshConfigE2eTest {
    private var root: Path? = null
    private var demo: Path? = null

    @BeforeTest
    fun prepare() {
        if (!RemoteTestEnvironment.available) return
        RemoteTestEnvironment.prepare()
        val workspace = Files.createTempDirectory(RemoteTestEnvironment.home, "catalog-")
        val project = workspace.resolve("demo")
        project.resolve("src").createDirectories()
        project.resolve("README.md").writeText("# Remote\n")
        root = workspace
        demo = project
        val file = workspace.resolve("ssh-config")
        file.writeText(
            """
            Host test-remote
              HostName 127.0.0.1
              User tester
              Port ${RemoteTestEnvironment.ssh.port}
              IdentityFile ${RemoteTestEnvironment.ssh.identity}
            """.trimIndent(),
        )
        System.setProperty("kotlin.editor.ssh.config", file.toString())
    }

    @AfterTest
    fun cleanup() {
        System.clearProperty("kotlin.editor.ssh.config")
        root?.toFile()?.deleteRecursively()
    }

    @Test
    fun `the dialog lists ssh config hosts and connects through an alias`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(RemoteTags.sshHost("test-remote")).performClick()
            onNodeWithTag(RemoteTags.REMOTE_CONFIG).assertTextContains("test-remote", substring = true)
            onNodeWithTag(RemoteTags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(RemoteTags.REMOTE_CONNECT).performClick()
            awaitTag(TreeTags.treeRow("README.md"), timeout = 60_000)
        }

    @Test
    fun `the ssh catalog can be searched`() =
        remoteUiTest {
            launchApp()
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.sshHost("test-remote"), timeout = 30_000)

            onNodeWithTag(RemoteTags.REMOTE_SEARCH).performTextInput("no such host")
            awaitNoTag(RemoteTags.sshHost("test-remote"))
            onNodeWithTag(RemoteTags.REMOTE_SEARCH).performTextClearance()
            awaitTag(RemoteTags.sshHost("test-remote"))
        }

    @Test
    fun `the manual form is hidden until New host is clicked`() =
        remoteUiTest {
            launchApp()
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitCondition { onAllNodes(hasTestTag(RemoteTags.REMOTE_HOST)).fetchSemanticsNodes().isEmpty() }

            onNodeWithTag(RemoteTags.REMOTE_NEW_HOST).performClick()
            awaitTag(RemoteTags.REMOTE_HOST)
            onNodeWithTag(RemoteTags.REMOTE_NEW_HOST).performClick()
            awaitCondition { onAllNodes(hasTestTag(RemoteTags.REMOTE_HOST)).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun `a chosen remote folder is saved and searchable after a restart`() {
        if (!RemoteTestEnvironment.available) return
        val project = checkNotNull(demo)
        val preferences = InMemoryPreferenceStore()
        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(RemoteTags.sshHost("test-remote")).performClick()
            onNodeWithTag(RemoteTags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(RemoteTags.REMOTE_CONNECT).performClick()
            awaitTag(TreeTags.treeRow("README.md"), timeout = 60_000)
            awaitCondition {
                preferences.load()[RemotePreferenceKeys.WORKSPACES].orEmpty().contains(project.toString())
            }
        }

        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            val saved = RemoteTags.remoteWorkspace("test-remote", project.toString())
            awaitTag(saved, timeout = 30_000)

            onNodeWithTag(RemoteTags.REMOTE_SEARCH).performTextInput("no such folder")
            awaitNoTag(saved)
            onNodeWithTag(RemoteTags.REMOTE_SEARCH).performTextClearance()
            awaitTag(saved)
            onNodeWithTag(saved).performClick()
            onNodeWithTag(RemoteTags.REMOTE_PROJECT).assertTextContains(project.toString(), substring = true)
            onNodeWithTag(RemoteTags.REMOTE_CONFIG).assertTextContains("test-remote", substring = true)
        }
    }

    @Test
    fun `hosts can be tagged, searched by tag and tags survive a restart`() {
        if (!RemoteTestEnvironment.available) return
        val preferences = InMemoryPreferenceStore()
        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(RemoteTags.sshHost("test-remote")).performClick()
            awaitTag(RemoteTags.REMOTE_TAGS)
            onNodeWithTag(RemoteTags.REMOTE_TAGS).performTextInput("production, dev")

            awaitTag(RemoteTags.remoteTag("test-remote", "production"))
            awaitTag(RemoteTags.remoteTag("test-remote", "dev"))
            onNodeWithTag(RemoteTags.REMOTE_SEARCH).performTextInput("production")
            awaitTag(RemoteTags.sshHost("test-remote"))
            awaitCondition {
                preferences.load()[RemotePreferenceKeys.TAGS].orEmpty().contains("test-remote")
            }
        }

        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.remoteTag("test-remote", "production"), timeout = 30_000)

            onNodeWithTag(RemoteTags.remoteTag("test-remote", "production")).performClick()
            onNodeWithTag(RemoteTags.REMOTE_SEARCH).assertTextContains("production", substring = true)
            awaitTag(RemoteTags.sshHost("test-remote"))
        }
    }

    @Test
    fun `recent folders are listed above the ssh config hosts`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            awaitTag(RemoteTags.sshHost("test-remote"), timeout = 30_000)
            assertNotSelected(RemoteTags.sshHost("test-remote"))
            onNodeWithTag(RemoteTags.sshHost("test-remote")).performClick()
            assertSelected(RemoteTags.sshHost("test-remote"))
            onNodeWithTag(RemoteTags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(RemoteTags.REMOTE_CONNECT).performClick()
            awaitTag(TreeTags.treeRow("README.md"), timeout = 60_000)

            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            onNodeWithTag(AppTags.PROJECT_CLOSE).performClick()
            awaitTag(AppTags.WELCOME)
            onNodeWithTag(AppTags.WELCOME_REMOTE).performClick()
            awaitTag(RemoteTags.REMOTE_DIALOG)
            val saved = RemoteTags.remoteWorkspace("test-remote", project.toString())
            awaitTag(saved, timeout = 30_000)

            val folder = onNodeWithTag(saved).fetchSemanticsNode().boundsInRoot.top
            val host = onNodeWithTag(RemoteTags.sshHost("test-remote")).fetchSemanticsNode().boundsInRoot.top
            assertTrue(folder < host, "recent folders must come before the SSH config hosts")
        }

    private fun ComposeUiTest.assertSelected(tag: String) {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(
            node.config.getOrNull(SemanticsProperties.Selected) == true,
            "the row tagged $tag should be highlighted as selected",
        )
    }

    private fun ComposeUiTest.assertNotSelected(tag: String) {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(
            node.config.getOrNull(SemanticsProperties.Selected) != true,
            "the row tagged $tag should not be highlighted",
        )
    }
}
