package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLOW_LINK_MS = 400L

/** Switching project from the toolbar menu while the network or the server fails, or the disk changes. */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherChaosE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject().apply { file("Other.kt", "class Other") }
    private val third = SampleProject().apply { file("Third.kt", "class Third") }

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
        third.delete()
    }

    @Test
    fun `a project chosen while the network is down opens once it is back`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), chaos = true)
            openRecentFromWelcome(project.path)
            app.network.goDown()
            awaitReconnecting()
            switchProjectTo(other.path)
            // The user sees that the switch is pending, and the project left stays usable meanwhile.
            awaitTag(Tags.STATUS_OPENING).assertTextContains("Opening", substring = true)
            awaitTag(Tags.treeRow("README.md"))
            app.network.comeBack()
            awaitTag(Tags.treeRow("Other.kt"))
            awaitNoTag(Tags.STATUS_OPENING)
            awaitNoTag(Tags.STATUS_ERROR)
        }

    @Test
    fun `on a slow link the last project chosen wins over a late answer`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path, third.path), chaos = true)
            openRecentFromWelcome(project.path)
            app.network.latencyMs = SLOW_LINK_MS
            switchProjectTo(other.path)
            // Changed mind before the first answer arrived.
            switchProjectTo(third.path)
            awaitTag(Tags.treeRow("Third.kt"))
            awaitNoTag(Tags.STATUS_OPENING)
            app.network.latencyMs = 0L
            // The project chosen first never opened: it would have become the most recent one before the third.
            awaitCondition { app.recentPaths == listOf(third.path, project.path, other.path) }
            assertEquals(third.root.fileName.toString(), app.state.title)
        }

    @Test
    fun `switching project works after the server restarted`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), chaos = true)
            openRecentFromWelcome(project.path)
            app.process.restart()
            switchProjectTo(other.path)
            awaitTag(Tags.treeRow("Other.kt"))
            awaitReconnected()
            awaitNoTag(Tags.STATUS_ERROR)
        }

    @Test
    fun `a recent project deleted by another program says so and keeps the open project`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path), chaos = true)
            openRecentFromWelcome(project.path)
            val opened = app.project
            other.delete()
            switchProjectTo(other.path)
            awaitTag(Tags.STATUS_ERROR).assertTextContains("Directory not found", substring = true)
            awaitNoTag(Tags.STATUS_OPENING)
            assertTrue(app.project === opened)
            awaitTag(Tags.treeRow("README.md"))
        }
}
