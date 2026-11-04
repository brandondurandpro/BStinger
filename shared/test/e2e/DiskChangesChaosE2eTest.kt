package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Other programs change the disk while the network or the server fails: the IDE catches up once they are back. */
@OptIn(ExperimentalTestApi::class)
class DiskChangesChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.awaitLine(index: Int, text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(EditorTags.line(index)).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }

    @Test
    fun `files changed while the network is down show once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFromTree("docs/guide.md")
            app.network.goDown()
            awaitReconnecting()
            project.file("docs/guide.md", "Changed while offline\n")
            project.file("Offline.kt", "class Offline\n")
            app.network.comeBack()
            awaitReconnected()
            awaitTag(TreeTags.treeRow("Offline.kt"))
            awaitLine(0, "Changed while offline")
        }

    @Test
    fun `a file created while the link is silent shows once it answers again`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.blackHole = true
            awaitReconnecting()
            project.file("Silent.kt", "class Silent\n")
            app.network.blackHole = false
            awaitReconnected()
            awaitTag(TreeTags.treeRow("Silent.kt"))
            assertEquals(1, onAllNodesWithTag(TreeTags.treeRow("Silent.kt")).fetchSemanticsNodes().size)
        }

    @Test
    fun `files changed while the server restarts show once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            onNodeWithTag(TreeTags.treeChevron("docs")).performClick()
            awaitTag(TreeTags.treeRow("docs/guide.md"))
            openFromTree("README.md")
            app.process.stop()
            awaitReconnecting()
            // Nobody watches the disk meanwhile: the new server knows nothing of these changes.
            project.file("README.md", "# Changed while the server was down\n")
            project.file("docs/Restarted.md", "Notes\n")
            app.process.start()
            awaitReconnected()
            awaitTag(TreeTags.treeRow("docs/Restarted.md"))
            awaitLine(0, "Changed while the server was down")
            // The new server watches too.
            project.file("AfterRestart.kt", "class AfterRestart\n")
            awaitTag(TreeTags.treeRow("AfterRestart.kt"))
        }

    @Test
    fun `changes reach the tree over a slow link`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 300
            project.file("Slow.kt", "class Slow\n")
            awaitTag(TreeTags.treeRow("Slow.kt"))
            project.root
                .resolve("Slow.kt")
                .toFile()
                .delete()
            awaitNoTag(TreeTags.treeRow("Slow.kt"))
        }
}
