package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The session then its files take longer than the wait for them (AppState.RESTORE_WAIT_MILLIS): the project is
// shown before its tabs.
private const val SLOW_LINK_MS = 400L
private const val ACTIVE = "src/main/kotlin/util/Strings.kt"
private val SESSION = listOf("docs/guide.md", ACTIVE)

/** Reopening a project while the server answers late or the network fails. */
@OptIn(ExperimentalTestApi::class)
class ProjectRestoreChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `on a slow link the project is shown, then its tabs all at once`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), chaos = true)
            // Kept by the server, as in production: loading it is one more round trip.
            runBlocking {
                app.client
                    .appServices()
                    .sessions
                    .save(project.path, sessionOf(project, SESSION, ACTIVE))
            }
            app.network.latencyMs = SLOW_LINK_MS
            RestoreRecorder(app, project).use { recorder ->
                awaitTag(AppTags.recentProject(project.path)).performClick()
                awaitTag(AppTags.PROJECT)
                awaitTag(TreeTags.treeRow(ACTIVE))
                awaitNoTag(AppTags.STATUS_OPENING)
                waitForIdle()
                val tabLists = recorder.views.map { it.tabs }.distinct()
                // Empty until the session arrived, then complete: never one tab after the other.
                assertTrue(tabLists.all { it.isEmpty() || it == SESSION }, "Tabs seen: $tabLists")
                assertEquals(SESSION, tabLists.lastOrNull())
                val actives = recorder.views.mapNotNull { it.active }.distinct()
                assertEquals(listOf(ACTIVE), actives)
            }
            app.network.latencyMs = 0L
            awaitNoTag(TreeTags.treeRow("docs/guide.md"))
        }

    @Test
    fun `a session arriving late keeps the file the user opened meanwhile`() =
        runComposeUiTest {
            val sessions = LateSessionStore()
            sessions.save(project.path, sessionOf(project, SESSION, ACTIVE))
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            openRecentFromWelcome(project.path)
            openFromTree("README.md")
            sessions.release()
            awaitTag(EditorTags.tab(ACTIVE))
            waitForIdle()
            assertEquals(
                "README.md",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
            assertEquals(
                listOf("README.md", "guide.md", "Strings.kt"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
            // The tree follows the user's file, not the one the session had active.
            awaitNoTag(TreeTags.treeRow(ACTIVE))
        }

    @Test
    fun `a session restored while the network is down reopens its tabs once it is back`() =
        runComposeUiTest {
            val sessions = LateSessionStore()
            sessions.save(project.path, sessionOf(project, SESSION, ACTIVE))
            val app = launchApp(recent = listOf(project.path), sessions = sessions, chaos = true)
            openRecentFromWelcome(project.path)
            app.network.goDown()
            awaitReconnecting()
            sessions.release()
            // Nothing pretends to be restored meanwhile, and the project stays usable.
            waitForIdle()
            assertEquals(emptyList(), app.project.editor.tabs)
            awaitTag(TreeTags.treeRow("README.md"))
            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.tab(ACTIVE))
            awaitTag(TreeTags.treeRow(ACTIVE))
            assertEquals(
                listOf("guide.md", "Strings.kt"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
        }
}
