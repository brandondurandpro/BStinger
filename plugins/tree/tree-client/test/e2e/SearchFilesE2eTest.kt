package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/** Go to File: the file lists behind it come from ripgrep when it runs, and from the built-in walk when it does not. */
@OptIn(ExperimentalTestApi::class)
class SearchFilesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openGoToFile() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } }
        }
        awaitTag(TreeTags.GOTO_FILE_INPUT)
    }

    @Test
    fun `Go to File lists the files of a new directory and none of the ignored directories`() =
        runComposeUiTest {
            project.file("huge/nested/Inside.kt", "")
            project.file("build/cache.txt", "")
            val app = launchWithProject(project)
            openGoToFile()
            awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput("nsid")
            awaitTag(TreeTags.gotoFileItem("huge/nested/Inside.kt")).assertIsDisplayed()
            // The index is loaded before the check: its list decides what Go to File could possibly show.
            awaitCondition { app.project.fileIndex.files != null }
            awaitNoTag(TreeTags.gotoFileItem("build/cache.txt"))
        }

    @Test
    fun `a file ignored by gitignore is left out while ripgrep runs and listed by the built-in walk`() =
        runComposeUiTest {
            // SampleProject has a `.git`: ripgrep applies `.gitignore` inside a Git repository only.
            project.file(".gitignore", "generated/\n")
            project.file("generated/brochures.md", "")
            val app = launchWithProject(project)
            openGoToFile()
            awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput("rochure")
            awaitCondition { app.project.fileIndex.files != null }
            waitForIdle()
            if (ripgrepRun()) {
                awaitNoTag(TreeTags.gotoFileItem("generated/brochures.md"))
            } else {
                awaitTag(TreeTags.gotoFileItem("generated/brochures.md")).assertIsDisplayed()
            }
        }

    @Test
    fun `Go to File still opens the file it lists`() =
        runComposeUiTest {
            project.file("huge/nested/Inside.kt", "// content")
            launchWithProject(project)
            openGoToFile()
            awaitTag(TreeTags.GOTO_FILE_INPUT).performTextInput("nsid")
            awaitTag(TreeTags.gotoFileItem("huge/nested/Inside.kt"))
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(EditorTags.tab("huge/nested/Inside.kt"))
            awaitNoTag(TreeTags.GOTO_FILE)
        }

    private companion object {
        /** Whether ripgrep will run (as the server would find it: the configured executable, or a downloaded copy). */
        fun ripgrepRun(): Boolean {
            val configured = System.getenv("KOTLIN_EDITOR_RIPGREP")
            if (!configured.isNullOrBlank()) {
                val executable = File(configured)
                return executable.exists() && executable.canExecute()
            }
            val versions = File(System.getProperty("user.home"), ".kotlin-editor/tools/ripgrep")
            val copies =
                versions
                    .listFiles()
                    .orEmpty()
                    .toList()
                    .flatMap { version -> version.listFiles().orEmpty().toList() }
            return copies.any { copy -> (copy.name == "rg" || copy.name == "rg.exe") && copy.canExecute() }
        }
    }
}
