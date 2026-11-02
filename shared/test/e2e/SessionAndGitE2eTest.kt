package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.InMemorySessionStore
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SessionAndGitE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun git(vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(project.root.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    @Test
    fun `reopening a project restores its tabs, active tab and carets`() =
        runComposeUiTest {
            val sessions = InMemorySessionStore()
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            onNodeWithTag(AppTags.recentProject(project.path)).performClick()
            awaitTag(TreeTags.treeRow("src"))
            openFromTree("README.md")
            openFromTree("docs/guide.md")
            runOnUiThread {
                app.project.editor.activeTab!!
                    .document!!
                    .moveTo(TextPos(1, 6))
            }
            onNodeWithTag(EditorTags.tab("README.md")).performClick()
            runOnUiThread { app.state.closeProject() }
            awaitTag(AppTags.WELCOME)
            onNodeWithTag(AppTags.recentProject(project.path)).performClick()
            awaitTag(EditorTags.tab("docs/guide.md"))
            awaitTag(EditorTags.tab("README.md"))
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "README.md"
            }
            assertEquals(
                listOf("README.md", "guide.md"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
            val guide =
                app.project.editor.tabs
                    .first { it.entry.name == "guide.md" }
            awaitCondition { guide.document?.caret == TextPos(1, 6) }
        }

    @Test
    fun `files that disappeared are skipped when restoring a session`() =
        runComposeUiTest {
            val sessions = InMemorySessionStore()
            sessions.save(
                project.path,
                ProjectSession(
                    listOf(
                        SessionFile(project.root.resolve("gone.kt").toString(), 0, 0),
                        SessionFile(project.root.resolve("README.md").toString(), 0, 2),
                    ),
                    activeFile = null,
                ),
            )
            val app = launchApp(recent = listOf(project.path), sessions = sessions)
            onNodeWithTag(AppTags.recentProject(project.path)).performClick()
            awaitTag(EditorTags.tab("README.md"))
            assertEquals(
                listOf("README.md"),
                app.project.editor.tabs
                    .map { it.entry.name },
            )
        }

    @Test
    fun `the project tree shows Git file status and updates it after saving`() =
        runComposeUiTest {
            git("init", "-q")
            git("add", "README.md", "build.gradle.kts", "src")
            git("commit", "-q", "-m", "init")
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            awaitTag(TreeTags.treeGitStatus("README.md", "Modified"))
            awaitTag(TreeTags.treeGitStatus("logo.png", "Untracked"))
            openFromTree("build.gradle.kts")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                pressKey(Key.X)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitTag(TreeTags.treeGitStatus("build.gradle.kts", "Modified"))
            assertEquals(null, app.project.git.status["src/main/kotlin/App.kt"])
        }
}
