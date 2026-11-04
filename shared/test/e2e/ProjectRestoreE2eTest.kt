package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.InMemorySessionStore
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ACTIVE = "src/main/kotlin/util/Strings.kt"
private val SESSION = listOf("README.md", "docs/guide.md", "src/test/kotlin/AppTest.kt", ACTIVE)
private val UNFOLDED_TO_ACTIVE = setOf("src", "src/main", "src/main/kotlin", "src/main/kotlin/util")
private const val MANY_TABS = 40

/** Reopening a project: its tabs and its tree appear as they were left, at once, without unfolding or switching. */
@OptIn(ExperimentalTestApi::class)
class ProjectRestoreE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a reopened project appears with all its tabs and its tree down to the active file, in one go`() =
        runComposeUiTest {
            project.commitAll()
            val sessions = InMemorySessionStore()
            sessions.save(project.path, sessionOf(project, SESSION, ACTIVE))
            val before = DiskSnapshot.take(project)
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            RestoreRecorder(app, project).use { recorder ->
                openRecentFromWelcome(project.path)
                awaitTag(TreeTags.treeRow(ACTIVE))
                awaitEditorReady()
                waitForIdle()
                // Never a tab after the other, another active tab, or the directories of the other tabs unfolded.
                assertEquals(listOf(RestoreView(SESSION, ACTIVE, UNFOLDED_TO_ACTIVE)), recorder.views)
            }
            awaitNoTag(TreeTags.treeRow("docs/guide.md"))
            awaitNoTag(TreeTags.treeRow("src/test/kotlin/AppTest.kt"))
            // Reopening files writes nothing.
            before.assertUnchanged(project)
        }

    @Test
    fun `the active tab of a session with more tabs than fit is shown`() =
        runComposeUiTest {
            val files = List(MANY_TABS) { "many/File${it.toString().padStart(2, '0')}.kt" }
            files.forEach { project.file(it, "class ${it.substringAfter('/').removeSuffix(".kt")}\n") }
            val active = files[MANY_TABS - 1]
            val sessions = InMemorySessionStore()
            sessions.save(project.path, sessionOf(project, files, active))
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            RestoreRecorder(app, project).use { recorder ->
                openRecentFromWelcome(project.path)
                awaitTag(TreeTags.treeRow(active))
                waitForIdle()
                assertEquals(listOf(RestoreView(files, active, setOf("many"))), recorder.views)
            }
            awaitTag(EditorTags.tab(active)).assertIsDisplayed()
        }

    @Test
    fun `when the active file is gone the last tab reopened is active`() =
        runComposeUiTest {
            val sessions = InMemorySessionStore()
            sessions.save(project.path, sessionOf(project, listOf("README.md", "docs/guide.md"), "gone/Gone.kt"))
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            openRecentFromWelcome(project.path)
            awaitTag(EditorTags.tab("docs/guide.md"))
            awaitTag(TreeTags.treeRow("docs/guide.md"))
            assertEquals(
                "guide.md",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
            assertEquals(
                listOf("README.md", "guide.md"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
        }

    @Test
    fun `with Always Select Opened File off the tree stays folded`() =
        runComposeUiTest {
            val sessions = InMemorySessionStore()
            sessions.save(project.path, sessionOf(project, SESSION, ACTIVE))
            val preferences = InMemoryPreferenceStore(mapOf(PreferenceKeys.AUTO_SELECT_OPENED_FILE to "false"))
            val app = launchApp(recent = listOf(project.path), sessions = sessions, preferences = preferences)
            RestoreRecorder(app, project).use { recorder ->
                openRecentFromWelcome(project.path)
                awaitTag(EditorTags.tab(ACTIVE))
                awaitEditorReady()
                waitForIdle()
                assertEquals(listOf(RestoreView(SESSION, ACTIVE, emptySet())), recorder.views)
            }
            awaitNoTag(TreeTags.treeRow(ACTIVE))
        }

    @Test
    fun `a project without a session opens with its tree only`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path), sessions = InMemorySessionStore())
            openRecentFromWelcome(project.path)
            awaitTag(TreeTags.treeRow("README.md"))
            waitForIdle()
            assertEquals(emptyList(), app.project.editor.tabs)
        }
}
