package org.example.kotlineditor.e2e

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val MULTI_LINE_TAG =
    listOf("<template>", "  <Navbar", """    class="a"""", """    :title="t"""", "</template>", "").joinToString("\n")
private val TEMPLATE = listOf("<template>", "  ", "</template>", "").joinToString("\n")

/** Typing the `>` of an opening tag in a Vue or HTML file adds the closing tag and leaves the caret between them. */
@OptIn(ExperimentalTestApi::class)
class TagClosingE2eTest {
    private val project =
        FakePhpServers.webProject().apply {
            file("src/Page.vue", TEMPLATE)
            file("public/index.html", TEMPLATE)
            file("src/Multi.vue", MULTI_LINE_TAG)
            file("src/notes.ts", "const a = 1\n")
        }
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.document(): Document = checkNotNull(project.editor.activeTab?.document)

    private fun ComposeUiTest.typeOpeningTag(app: E2eApp, path: String, line: Int, column: Int) {
        openFromTree(path)
        onNodeWithTag(EditorTags.EDITOR).performClick()
        app.document().moveTo(TextPos(line, column))
        typeCode("<Navbar")
        typeGreaterThan()
    }

    // A key that types `>`: the key injection of the test framework gives the character of the key without Shift.
    @OptIn(InternalComposeUiApi::class)
    private fun ComposeUiTest.typeGreaterThan() {
        val greaterThan =
            KeyEvent(key = Key.Period, type = KeyEventType.KeyDown, codePoint = '>'.code, isShiftPressed = true)
        onNodeWithTag(EditorTags.EDITOR).performKeyPress(greaterThan)
        waitForIdle()
    }

    @Test
    fun `in a Vue file the closing tag is added and the caret stays after the opening one`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            typeOpeningTag(app, "src/Page.vue", 1, 2)
            assertEquals("  <Navbar></Navbar>", app.document().line(1))
            assertEquals(TextPos(1, "  <Navbar>".length), app.document().caret)
        }

    @Test
    fun `a tag whose attributes span several lines is closed after the last one`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/Multi.vue")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            app.document().moveTo(TextPos(3, "    :title=\"t\"".length))
            typeGreaterThan()
            assertEquals("    :title=\"t\"></Navbar>", app.document().line(3))
            assertEquals(TextPos(3, "    :title=\"t\">".length), app.document().caret)
        }

    @Test
    fun `in an HTML file too`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            typeOpeningTag(app, "public/index.html", 1, 2)
            assertEquals("  <Navbar></Navbar>", app.document().line(1))
        }

    @Test
    fun `a TypeScript file keeps what is typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            typeOpeningTag(app, "src/notes.ts", 0, 0)
            assertEquals("<Navbar>const a = 1", app.document().line(0))
        }
}
