package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.InMemorySessionStore
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OPEN_FILES = 200
private const val DEPTH = 12
private const val FILES_PER_DIRECTORY = 300

/**
 * Performance of reopening a project whose session has [OPEN_FILES] tabs, the active one [DEPTH] directories deep
 * among directories of [FILES_PER_DIRECTORY] files: the files and the directories are fetched together, then shown in
 * one frame. Prints a `PERF` line and has a budget.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectRestorePerformanceE2eTest {
    private val directories = List(DEPTH) { depth -> List(depth + 1) { "d$it" }.joinToString("/") }
    private val active = "${directories.lastOrNull()}/Deep.kt"
    private val project =
        SampleProject().apply {
            for (directory in directories) {
                repeat(FILES_PER_DIRECTORY) { file("$directory/f${it.toString().padStart(3, '0')}.txt", "$it\n") }
            }
            file(active, "class Deep\n")
        }
    private val session =
        List(OPEN_FILES - 1) { "${directories[it % DEPTH]}/f${it.toString().padStart(3, '0')}.txt" } + active

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `reopening a project with many tabs and a deep active file stays fast`() =
        runComposeUiTest {
            val sessions = InMemorySessionStore()
            sessions.save(project.path, sessionOf(project, session, active))
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            RestoreRecorder(app, project).use { recorder ->
                measure("reopen a project of $OPEN_FILES tabs, $DEPTH levels deep", budgetMillis = 10_000) {
                    awaitTag(AppTags.recentProject(project.path))
                    onNodeWithTag(AppTags.recentProject(project.path)).performClick()
                    awaitTag(TreeTags.treeRow(active))
                    renderFrame()
                }
                assertEquals(listOf(RestoreView(session, active, directories.toSet())), recorder.views)
            }
        }
}
