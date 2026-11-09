package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documentation on hover, Ctrl+click and Ctrl+B to declarations, usages and their counts, occurrences of the
 * symbol at the caret, in PHP files, from the language server, through the UI.
 */
@OptIn(ExperimentalTestApi::class)
class CodeInsightE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.document(): Document = project.editor.activeTab!!.document!!

    // Null while the file opened by a navigation loads.
    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    private fun E2eApp.activePath(): String = project.editor.activeTab!!.relativePath

    // Opens [path] with the language server running and the problems of the file known.
    private fun ComposeUiTest.open(path: String = "src/index.php"): E2eApp {
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `hovering a method shows its documentation, and moving away hides it`() =
        runComposeUiTest {
            open()
            // `echo $greeter->greet("world", 1);`: the mouse rests on greet.
            hoverAt(2, 17)
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            val documentation = textOf(EditorTags.HOVER_DOCUMENTATION)
            assertTrue("Greeter # greet" in documentation, documentation)
            assertTrue("Says hello to someone." in documentation, documentation)
            assertTrue("Who to greet." in documentation, documentation)
            assertTrue("public function greet(string \$name, int \$times): string" in documentation, documentation)
            mouseOutOfEditor()
            awaitNoTag(EditorTags.HOVER)
        }

    @Test
    fun `hovering a class shows its documentation, hovering the PHP tag shows none`() =
        runComposeUiTest {
            open()
            // `$greeter = new Greeter();`
            hoverAt(1, 17)
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            assertTrue("Greets people politely." in textOf(EditorTags.HOVER_DOCUMENTATION))
            // `<?php`, above the popup: nothing the server documents.
            hoverAt(0, 3)
            awaitNoTag(EditorTags.HOVER)
        }

    @Test
    fun `Ctrl+Q shows the documentation at the caret and Escape closes it`() =
        runComposeUiTest {
            open()
            clickAt(2, 18)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Q) } }
            awaitTag(EditorTags.HOVER_DOCUMENTATION, timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.HOVER)
        }

    @Test
    fun `Ctrl+click goes to the declaration in another file, on its name, and back`() =
        runComposeUiTest {
            val app = open()
            ctrlClickAt(2, 17)
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            // The server gives the range of the whole method: the caret lands on its name.
            awaitCondition { app.caret() == TextPos(13, 20) }
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } } }
            awaitCondition { app.activePath() == "src/index.php" }
        }

    @Test
    fun `Ctrl+click on a function declared far down another file scrolls to its declaration`() =
        runComposeUiTest {
            val filler = List(FAR_LINE - 1) { "// filler $it" }
            project.file("src/Far.php", (listOf("<?php") + filler + "function farAway() {}" + "").joinToString("\n"))
            project.file("src/call.php", listOf("<?php", "farAway();", "").joinToString("\n"))
            val app = open("src/call.php")
            ctrlClickAt(1, 2)
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Far.php" }
            awaitCondition { app.caret() == TextPos(FAR_LINE, "function ".length) }
            // The file opens on the declaration, not at its top.
            awaitTag(EditorTags.lineText(FAR_LINE), unmerged = true)
            awaitNoTag(EditorTags.lineText(0))
        }

    @Test
    fun `holding Ctrl over a symbol underlines it like a link`() =
        runComposeUiTest {
            open()
            ctrlHoverAt(2, 17)
            awaitTag(EditorTags.LINK, timeout = SERVER_START_MS, unmerged = true)
            assertEquals("greet", textOf(EditorTags.LINK))
        }

    @Test
    fun `Ctrl+B on a declaration lists its usages, and Enter opens the chosen one`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            clickAt(13, 22)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            assertEquals("Usages of greet  2 usages", textOf(EditorTags.LOCATIONS_TITLE))
            onNodeWithTag(EditorTags.location(0)).assertIsSelected()
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.location(1)).assertIsSelected()
            editorKeys { pressKey(Key.Enter) }
            awaitCondition { app.activePath() == "src/index.php" }
            awaitCondition { app.caret() == TextPos(3, 15) }
            awaitNoTag(EditorTags.LOCATIONS)
        }

    @Test
    fun `the places of a list are in three aligned columns`() =
        runComposeUiTest {
            open("src/Greeter.php")
            clickAt(13, 22)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            val columns =
                listOf(EditorTags::locationFile, EditorTags::locationPreview, EditorTags::locationDirectory)
            for (column in columns) {
                val first = onNodeWithTag(column(0)).fetchSemanticsNode().positionInRoot.x
                val second = onNodeWithTag(column(1)).fetchSemanticsNode().positionInRoot.x
                assertEquals(first, second, "the rows start their column at the same place")
            }
            val file = onNodeWithTag(EditorTags.locationFile(0)).fetchSemanticsNode().positionInRoot.x
            val code = onNodeWithTag(EditorTags.locationPreview(0)).fetchSemanticsNode().positionInRoot.x
            val directory = onNodeWithTag(EditorTags.locationDirectory(0)).fetchSemanticsNode().positionInRoot.x
            assertTrue(file < code && code < directory, "file, then code, then directory")
            val width = onNodeWithTag(EditorTags.LOCATIONS).fetchSemanticsNode().size.width
            val limit = density.run { 1000.dp.toPx() }
            assertTrue(width <= limit, "the popup fits its content within its limit: $width px")
        }

    @Test
    fun `each declaration shows its number of usages, and a click on it lists them`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            awaitTag(EditorTags.usageHint(13), timeout = SERVER_START_MS, unmerged = true)
            assertEquals("2 usages", textOf(EditorTags.usageHint(13)))
            assertEquals("1 usage", textOf(EditorTags.usageHint(5)))
            onNodeWithTag(EditorTags.usageHint(13), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.LOCATIONS)
            onNodeWithTag(EditorTags.location(0)).performClick()
            awaitCondition { app.activePath() == "src/index.php" }
            awaitCondition { app.caret() == TextPos(2, 15) }
        }

    @Test
    fun `the pointer is a hand over a usage hint, and the text cursor elsewhere`() =
        runComposeUiTest {
            val app = open("src/Greeter.php")
            awaitTag(EditorTags.usageHint(13), timeout = SERVER_START_MS, unmerged = true)
            val hint = onNodeWithTag(EditorTags.usageHint(13), useUnmergedTree = true)
            hint.performMouseInput { moveTo(center) }
            awaitCondition { app.project.insight.hintUnderMouse === app.document() }
            // On the code of the same line: not a link.
            hoverAt(13, 10)
            awaitCondition { app.project.insight.hintUnderMouse == null }
            hint.performMouseInput { moveTo(center) }
            awaitCondition { app.project.insight.hintUnderMouse === app.document() }
            mouseOutOfEditor()
            awaitCondition { app.project.insight.hintUnderMouse == null }
        }

    @Test
    fun `Alt+F7 lists the usages, Escape closes the list, and a symbol without usages says so`() =
        runComposeUiTest {
            open("src/Greeter.php")
            clickAt(13, 22)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.LOCATIONS)
            // `class Shy`: a class nobody uses.
            openFromTree("src/Shy.php")
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            clickAt(2, 7)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
            awaitTag(EditorTags.NOTICE)
            assertEquals("No usages found", textOf(EditorTags.NOTICE))
        }

    @Test
    fun `Ctrl+Shift+B goes to the class of a variable, Ctrl+Alt+B lists the implementations`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 8)
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.B) } } }
            awaitCondition(SERVER_START_MS) { app.activePath() == "src/Greeter.php" }
            awaitCondition { app.caret() == TextPos(5, 6) }
            // `class Greeter implements Polite`: the two classes that implement it.
            clickAt(5, 27)
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.B) } } }
            awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
            assertEquals("Choose implementation of Polite", textOf(EditorTags.LOCATIONS_TITLE))
            onNodeWithTag(EditorTags.location(1)).performClick()
            awaitCondition { app.activePath() == "src/Shy.php" }
        }

    @Test
    fun `the occurrences of the variable at the caret are highlighted, its assignment as a write`() =
        runComposeUiTest {
            open()
            clickAt(2, 8)
            awaitTag(EditorTags.symbolHighlight(1, "Write"), timeout = SERVER_START_MS, unmerged = true)
            awaitTag(EditorTags.symbolHighlight(2, "Read"), unmerged = true)
            awaitTag(EditorTags.symbolHighlight(3, "Read"), unmerged = true)
            // On `echo`: nothing to highlight.
            clickAt(2, 1)
            awaitCondition {
                onAllNodes(hasTestTag(EditorTags.symbolHighlight(1, "Write")), true).fetchSemanticsNodes().isEmpty()
            }
        }

    @Test
    fun `a symbol declared nowhere cannot be navigated to`() =
        runComposeUiTest {
            open()
            onNodeWithTag(EditorTags.EDITOR).performClick()
            caretToEnd()
            typeCode("nowhere")
            editorKeys { pressKey(Key.DirectionLeft) }
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("Cannot find declaration to go to", textOf(EditorTags.NOTICE))
        }
}

// Far below the first screen of a file.
private const val FAR_LINE = 300
