package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.RemotePreferenceKeys
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
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
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(ItemTags.sshHost("test-remote")).performClick()
            onNodeWithTag(Tags.REMOTE_CONFIG).assertTextContains("test-remote", substring = true)
            onNodeWithTag(Tags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
        }

    @Test
    fun `the ssh catalog can be searched`() =
        remoteUiTest {
            launchApp()
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.sshHost("test-remote"), timeout = 30_000)

            onNodeWithTag(Tags.REMOTE_SEARCH).performTextInput("no such host")
            awaitNoTag(ItemTags.sshHost("test-remote"))
            onNodeWithTag(Tags.REMOTE_SEARCH).performTextClearance()
            awaitTag(ItemTags.sshHost("test-remote"))
        }

    @Test
    fun `the manual form is hidden until New host is clicked`() =
        remoteUiTest {
            launchApp()
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitCondition { onAllNodes(hasTestTag(Tags.REMOTE_HOST)).fetchSemanticsNodes().isEmpty() }

            onNodeWithTag(Tags.REMOTE_NEW_HOST).performClick()
            awaitTag(Tags.REMOTE_HOST)
            onNodeWithTag(Tags.REMOTE_NEW_HOST).performClick()
            awaitCondition { onAllNodes(hasTestTag(Tags.REMOTE_HOST)).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun `a chosen remote folder is saved and searchable after a restart`() {
        if (!RemoteTestEnvironment.available) return
        val project = checkNotNull(demo)
        val preferences = InMemoryPreferenceStore()
        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(ItemTags.sshHost("test-remote")).performClick()
            onNodeWithTag(Tags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)
            awaitCondition {
                preferences.load()[RemotePreferenceKeys.WORKSPACES].orEmpty().contains(project.toString())
            }
        }

        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            val saved = ItemTags.remoteWorkspace("test-remote", project.toString())
            awaitTag(saved, timeout = 30_000)

            onNodeWithTag(Tags.REMOTE_SEARCH).performTextInput("no such folder")
            awaitNoTag(saved)
            onNodeWithTag(Tags.REMOTE_SEARCH).performTextClearance()
            awaitTag(saved)
            onNodeWithTag(saved).performClick()
            onNodeWithTag(Tags.REMOTE_PROJECT).assertTextContains(project.toString(), substring = true)
            onNodeWithTag(Tags.REMOTE_CONFIG).assertTextContains("test-remote", substring = true)
        }
    }

    @Test
    fun `hosts can be tagged, searched by tag and tags survive a restart`() {
        if (!RemoteTestEnvironment.available) return
        val preferences = InMemoryPreferenceStore()
        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(ItemTags.sshHost("test-remote")).performClick()
            awaitTag(Tags.REMOTE_TAGS)
            onNodeWithTag(Tags.REMOTE_TAGS).performTextInput("production, dev")

            awaitTag(ItemTags.remoteTag("test-remote", "production"))
            awaitTag(ItemTags.remoteTag("test-remote", "dev"))
            onNodeWithTag(Tags.REMOTE_SEARCH).performTextInput("production")
            awaitTag(ItemTags.sshHost("test-remote"))
            awaitCondition {
                preferences.load()[RemotePreferenceKeys.TAGS].orEmpty().contains("test-remote")
            }
        }

        remoteUiTest {
            launchApp(preferences = preferences)
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.remoteTag("test-remote", "production"), timeout = 30_000)

            onNodeWithTag(ItemTags.remoteTag("test-remote", "production")).performClick()
            onNodeWithTag(Tags.REMOTE_SEARCH).assertTextContains("production", substring = true)
            awaitTag(ItemTags.sshHost("test-remote"))
        }
    }

    @Test
    fun `recent folders are listed above the ssh config hosts`() =
        remoteUiTest {
            val project = checkNotNull(demo)
            launchApp()
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            awaitTag(ItemTags.sshHost("test-remote"), timeout = 30_000)
            onNodeWithTag(ItemTags.sshHost("test-remote")).performClick()
            onNodeWithTag(Tags.REMOTE_PROJECT).performTextInput(project.toString())
            onNodeWithTag(Tags.REMOTE_CONNECT).performClick()
            awaitTag(Tags.treeRow("README.md"), timeout = 60_000)

            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            onNodeWithTag(Tags.PROJECT_CLOSE).performClick()
            awaitTag(Tags.WELCOME)
            onNodeWithTag(Tags.WELCOME_REMOTE).performClick()
            awaitTag(Tags.REMOTE_DIALOG)
            val saved = ItemTags.remoteWorkspace("test-remote", project.toString())
            awaitTag(saved, timeout = 30_000)

            val folder = onNodeWithTag(saved).fetchSemanticsNode().boundsInRoot.top
            val host = onNodeWithTag(ItemTags.sshHost("test-remote")).fetchSemanticsNode().boundsInRoot.top
            assertTrue(folder < host, "recent folders must come before the SSH config hosts")
        }
}
