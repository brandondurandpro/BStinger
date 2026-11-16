package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorConfigTags
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private val BAD_FILE = "src/main/kotlin/Bad.kt"
private val CLEAN_FILE = "src/main/kotlin/Clean.kt"

// The line 5 is indented with 3 spaces: not a multiple of the 2 the config asks for (the line 4, with 4, is fine).
private val BAD_FILE_TEXT =
    textOf(
        "package demo",
        "",
        "class Bad {",
        "    fun wide() {}",
        "   fun off() {}",
        "}",
        "",
    )

private val CLEAN_FILE_TEXT = textOf("package demo", "", "class Clean {", "  fun narrow() {}", "}", "")

/** The EditorConfig tool window: the rules of `.editorconfig` the open files break, listed by file and line. */
@OptIn(ExperimentalTestApi::class)
class EditorConfigE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun writeConfig() {
        project.file(
            ".editorconfig",
            textOf("root = true", "", "[*.kt]", "indent_style = space", "indent_size = 2", ""),
        )
    }

    @Test
    fun `the window lists the violations of the open files with their file and line`() = runComposeUiTest {
        writeConfig()
        project.file(BAD_FILE, BAD_FILE_TEXT)
        launchWithProject(project)
        openFromTree(BAD_FILE)
        onNodeWithTag(EditorConfigTags.TOOL).performClick()
        awaitTag(EditorConfigTags.PANEL)
        awaitTag(EditorConfigTags.fileGroup(BAD_FILE))
        awaitTag(EditorConfigTags.item(BAD_FILE, 5, "indent-size"))
    }

    @Test
    fun `clicking a violation opens its file in the editor`() = runComposeUiTest {
        writeConfig()
        project.file(BAD_FILE, BAD_FILE_TEXT)
        launchWithProject(project)
        openFromTree(BAD_FILE)
        onNodeWithTag(EditorConfigTags.TOOL).performClick()
        awaitTag(EditorConfigTags.item(BAD_FILE, 5, "indent-size"))
        // The tab is closed, so that the click of the violation is the one that opens the file again.
        awaitTag(EditorTags.tabClose(BAD_FILE)).performClick()
        awaitNoTag(EditorTags.tab(BAD_FILE))
        awaitTag(EditorConfigTags.item(BAD_FILE, 5, "indent-size")).performClick()
        awaitTag(EditorTags.tab(BAD_FILE))
        awaitEditorReady()
    }

    @Test
    fun `a file that breaks no rule says so`() = runComposeUiTest {
        writeConfig()
        project.file(CLEAN_FILE, CLEAN_FILE_TEXT)
        launchWithProject(project)
        openFromTree(CLEAN_FILE)
        onNodeWithTag(EditorConfigTags.TOOL).performClick()
        awaitTag(EditorConfigTags.EMPTY)
    }

    @Test
    fun `a project without a config file says so`() = runComposeUiTest {
        project.file(CLEAN_FILE, CLEAN_FILE_TEXT)
        launchWithProject(project)
        openFromTree(CLEAN_FILE)
        onNodeWithTag(EditorConfigTags.TOOL).performClick()
        awaitTag(EditorConfigTags.EMPTY)
    }
}
