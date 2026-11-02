package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TextClipboard
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Problems, parameter info, rename, reformat and quick fixes in PHP files, from the language server, through the
 * UI: what the user sees and what ends up in the editor and on disk.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditingInsightE2eTest {
    private val project = codeInsightProject()
    private val created = mutableListOf<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun servers(vararg options: String) = FakePhpServers(*options).chosen().also { created += it }

    private fun E2eApp.document(): Document = project.editor.activeTab!!.document!!

    private fun disk(path: String): String = Files.readString(project.root.resolve(path))

    // Opens [path] with the language server running and its problems known, the caret at the end.
    private fun ComposeUiTest.open(path: String = "src/index.php", vararg options: String): E2eApp {
        val app = launchWithProject(project, languageServers = servers(*options).settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        onNodeWithTag(EditorTags.EDITOR).performClick()
        caretToEnd()
        return app
    }

    private fun ComposeUiTest.problemCount(tag: String): String? =
        textOf(
            tag,
        ).takeIf {
            onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(tag),
                true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `an unknown function is underlined, counted, explained on hover, and F2 goes to it`() =
        runComposeUiTest {
            val app = open()
            assertEquals(null, problemCount(EditorTags.PROBLEMS_ERRORS))
            typeCode("missing();\n")
            awaitTag(EditorTags.diagnostic(4, "Error"), timeout = SERVER_START_MS, unmerged = true)
            awaitCondition { problemCount(EditorTags.PROBLEMS_ERRORS) == "1" }
            hoverAt(4, 2)
            awaitTag(EditorTags.HOVER_PROBLEMS, timeout = SERVER_START_MS)
            assertTrue("Function \"missing\" not found" in textOf(EditorTags.HOVER_PROBLEMS))
            mouseOutOfEditor()
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            editorKeys { pressKey(Key.F2) }
            assertEquals(TextPos(4, 0), app.document().caret)
            // Fixed: the problem goes.
            editorKeys { pressKey(Key.MoveEnd) }
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.MoveHome) } }
            editorKeys { pressKey(Key.Delete) }
            awaitNoTag(EditorTags.PROBLEMS_ERRORS, timeout = SERVER_START_MS)
        }

    @Test
    fun `a problem follows the text typed before it until the server answers`() =
        runComposeUiTest {
            val app = open()
            typeCode("missing();")
            awaitTag(EditorTags.diagnostic(4, "Error"), timeout = SERVER_START_MS, unmerged = true)
            editorKeys { pressKey(Key.MoveHome) }
            editorKeys { pressKey(Key.Enter) }
            awaitTag(EditorTags.diagnostic(5, "Error"), timeout = SERVER_START_MS, unmerged = true)
            assertEquals("missing();", app.document().lines[5])
        }

    @Test
    fun `a deprecated call is a warning, F2 then Shift+F2 cycle through the problems`() =
        runComposeUiTest {
            val app = open()
            typeCode("missing();\n// @deprecated-call\n")
            awaitCondition(SERVER_START_MS) { problemCount(EditorTags.PROBLEMS_WARNINGS) == "1" }
            assertEquals("1", problemCount(EditorTags.PROBLEMS_ERRORS))
            editorKeys { pressKey(Key.F2) }
            assertEquals(TextPos(4, 0), app.document().caret)
            editorKeys { pressKey(Key.F2) }
            assertEquals(TextPos(5, 3), app.document().caret)
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F2) } }
            assertEquals(TextPos(4, 0), app.document().caret)
        }

    @Test
    fun `typing an opening parenthesis shows the parameters, the comma moves to the next one`() =
        runComposeUiTest {
            open()
            typeCode("\$greeter->greet(")
            awaitTag(EditorTags.SIGNATURE, timeout = SERVER_START_MS)
            awaitCondition { textOf(EditorTags.SIGNATURE_ACTIVE_PARAMETER) == "string \$name" }
            typeCode("\"you\", ")
            awaitCondition { textOf(EditorTags.SIGNATURE_ACTIVE_PARAMETER) == "int \$times" }
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.SIGNATURE)
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.P) } } }
            awaitTag(EditorTags.SIGNATURE, timeout = SERVER_START_MS)
        }

    @Test
    fun `Ctrl+Shift+P outside a call says there are no parameters`() =
        runComposeUiTest {
            open()
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.P) } } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("No parameters to show", textOf(EditorTags.NOTICE))
        }

    @Test
    fun `Shift+F6 renames a method in the open file and in the closed one, in one undo step`() =
        runComposeUiTest {
            val app = open()
            clickAt(2, 18)
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
            awaitTag(EditorTags.RENAME_INPUT, timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.RENAME_INPUT).performTextReplacement("welcome")
            onNodeWithTag(EditorTags.RENAME_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(EditorTags.RENAME)
            awaitCondition(SERVER_START_MS) { "->welcome(\"world\", 1)" in app.document().lines[2] }
            assertTrue("->welcome(\"again\", 2)" in app.document().lines[3])
            // The declaration, in a file that is not open, is changed on disk.
            awaitCondition { "public function welcome(" in disk("src/Greeter.php") }
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertTrue("->greet(\"world\", 1)" in app.document().lines[2])
            assertTrue("->greet(\"again\", 2)" in app.document().lines[3])
        }

    @Test
    fun `Escape cancels a rename, and a keyword cannot be renamed`() =
        runComposeUiTest {
            val app = open()
            val before = app.document().lines.toList()
            clickAt(2, 18)
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
            awaitTag(EditorTags.RENAME_INPUT, timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.RENAME_INPUT).performTextReplacement("nothing")
            onNodeWithTag(EditorTags.RENAME_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.RENAME)
            assertEquals(before, app.document().lines)
            // `new`
            clickAt(1, 12)
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertFalse(
                onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(EditorTags.RENAME),
                ).fetchSemanticsNodes().isNotEmpty(),
            )
        }

    @Test
    fun `Ctrl+Alt+L reformats the file, and an undo brings it back`() =
        runComposeUiTest {
            val app = open()
            TextClipboard.set("if (true) {\n\techo 1;   \n}")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.L) } } }
            awaitCondition(SERVER_START_MS) { app.document().lines[5] == "    echo 1;" }
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals("\techo 1;   ", app.document().lines[5])
        }

    @Test
    fun `a server that cannot reformat says so and leaves the text`() =
        runComposeUiTest {
            val app = open("src/index.php", "--without", "formatting,rangeFormatting")
            val before = app.document().lines.toList()
            editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.L) } } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("The language server cannot reformat", textOf(EditorTags.NOTICE))
            assertEquals(before, app.document().lines)
        }

    @Test
    fun `Alt+Enter on an unknown call creates the function`() =
        runComposeUiTest {
            val app = open()
            typeCode("missing();")
            awaitTag(EditorTags.diagnostic(4, "Error"), timeout = SERVER_START_MS, unmerged = true)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            awaitTag(EditorTags.codeAction("Create function missing"), timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Enter) }
            awaitNoTag(EditorTags.CODE_ACTIONS)
            awaitCondition(SERVER_START_MS) { "function missing()" in app.document().lines }
            awaitNoTag(EditorTags.PROBLEMS_ERRORS, timeout = SERVER_START_MS)
        }

    @Test
    fun `Alt+Enter runs a command of the server that edits the file`() =
        runComposeUiTest {
            val app = open("src/Shy.php")
            clickAt(2, 7)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            awaitTag(EditorTags.codeAction("Add docblock"), timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.codeAction("Add docblock")).performClick()
            awaitCondition(SERVER_START_MS) { app.document().lines.getOrNull(2) == "/**" }
            assertTrue(app.document().lines.any { it.trim() == "class Shy implements Polite" })
        }

    @Test
    fun `Alt+Enter where nothing applies says so, Escape closes the list`() =
        runComposeUiTest {
            open()
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("No actions available here", textOf(EditorTags.NOTICE))
            typeCode("missing();")
            awaitTag(EditorTags.diagnostic(4, "Error"), timeout = SERVER_START_MS, unmerged = true)
            editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            awaitTag(EditorTags.CODE_ACTIONS, timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.CODE_ACTIONS)
            assertFalse("function missing()" in disk("src/index.php"))
        }
}
