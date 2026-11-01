package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Code completion in PHP files, from the language server, through the UI (see #97). */
@OptIn(ExperimentalTestApi::class)
class CompletionE2eTest {
    private val project = FakePhpServers.project()
    private val created = mutableListOf<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun servers(vararg options: String) = FakePhpServers(*options).chosen().also { created += it }

    private fun E2eApp.document(): Document = project.editor.activeTab!!.document!!

    // Opens the PHP file with the server running, the caret at its end.
    private fun ComposeUiTest.openPhp(app: E2eApp, path: String = "src/index.php") {
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        onNodeWithTag(Tags.EDITOR).performClick()
        caretToEnd()
    }

    @Test
    fun `typing proposes the functions of the open files, and Enter inserts one`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openFromTree("src/util.php")
            openPhp(app)
            typeCode("sho")
            awaitCompletion("shout")
            onNodeWithTag(Tags.completionItem("shout"), useUnmergedTree = true).assertIsSelected()
            editorKeys { pressKey(Key.Enter) }
            awaitNoTag(Tags.COMPLETION)
            assertEquals("shout", app.document().line(2))
        }

    @Test
    fun `a function typed a moment ago is proposed`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("function greetVisitor() {}\n")
            typeCode("gree")
            awaitCompletion("greetVisitor")
            editorKeys { pressKey(Key.Tab) }
            assertEquals("greetVisitor", app.document().line(3))
        }

    @Test
    fun `variables keep their dollar`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("\$total = 1;\n\$to")
            awaitCompletion("\$total")
            editorKeys { pressKey(Key.Enter) }
            assertEquals("\$total", app.document().line(3))
        }

    @Test
    fun `the list narrows while typing and the arrows choose`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("str")
            awaitCompletion("strlen")
            assertEquals(listOf("str_replace", "strlen", "strtoupper"), completionLabels())
            typeCode("t")
            awaitCondition { completionLabels() == listOf("strtoupper") }
            editorKeys { pressKey(Key.Backspace) }
            awaitCondition { completionLabels().size == 3 }
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(Tags.completionItem("strlen"), useUnmergedTree = true).assertIsSelected()
            editorKeys { pressKey(Key.DirectionUp) }
            editorKeys { pressKey(Key.DirectionUp) }
            onNodeWithTag(Tags.completionItem("strtoupper"), useUnmergedTree = true).assertIsSelected()
            editorKeys { pressKey(Key.Enter) }
            assertEquals("strtoupper", app.document().line(2))
        }

    @Test
    fun `the types line up on the right whatever the length of the names`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("s")
            awaitCompletion("strtoupper")
            val popup = app.project.completion.popup!!
            val details = popup.items.mapNotNull { it.detail }
            val rights =
                details.map { detail ->
                    val nodes =
                        onAllNodes(hasText(detail) and hasAnyAncestor(hasTestTag(Tags.COMPLETION)), true)
                            .fetchSemanticsNodes()
                    nodes.single().boundsInRoot.right
                }
            assertTrue(rights.size >= 3, "names of different lengths: $details")
            assertTrue(rights.max() - rights.min() < 1f, "right edges of the types: $rights")
        }

    @Test
    fun `Escape closes the popup and typing goes on`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("arr")
            awaitCompletion("array_map")
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(Tags.COMPLETION)
            // A space ends the word: no popup, the text is what was typed.
            typeCode(" x")
            editorKeys { pressKey(Key.DirectionDown) }
            assertEquals("arr x", app.document().line(2))
        }

    @Test
    fun `a click inserts an item, and undo brings the typed text back`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("strl")
            awaitCompletion("strlen")
            onNodeWithTag(Tags.completionItem("strlen"), useUnmergedTree = true).performClick()
            awaitNoTag(Tags.COMPLETION)
            assertEquals("strlen", app.document().line(2))
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals("strl", app.document().line(2))
        }

    @Test
    fun `Ctrl+Space lists everything after a space`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openFromTree("src/util.php")
            openPhp(app)
            typeCode("echo ")
            assertFalse(completionShown())
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
            awaitCompletion("array_map")
            awaitCondition { "shout" in completionLabels() }
            assertEquals(listOf("array_map", "shout", "str_replace", "strlen", "strtoupper"), completionLabels())
        }

    @Test
    fun `nothing is proposed where no item matches, nor in a file without a server`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            typeCode("zzq")
            // The server answered (an item matching "z" would show): nothing to show.
            assertFalse(completionShown())
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            caretToEnd()
            typeCode("str")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
            awaitNoTag(Tags.COMPLETION, timeout = 1_000)
            assertTrue(
                app
                    .document()
                    .lines
                    .last()
                    .endsWith("str"),
            )
        }

    @Test
    fun `a server that never gets ready proposes nothing and typing works`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--hang-on-initialize").settings)
            openFromTree("src/index.php")
            onNodeWithTag(Tags.EDITOR).performClick()
            caretToEnd()
            typeCode("strl")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
            awaitNoTag(Tags.COMPLETION, timeout = 1_000)
            assertEquals("strl", app.document().line(2))
        }

    @Test
    fun `a server restarted while typing proposes what was typed meanwhile`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers().settings)
            openPhp(app)
            val server = awaitServer(app) { it.isRunning() }
            awaitTag(Tags.statusLanguageServer(server.id)).performClick()
            awaitTag(Tags.languageServerRestart(server.id)).performClick()
            onNodeWithTag(Tags.EDITOR).performClick()
            caretToEnd()
            typeCode("function duringRestart() {}\n")
            awaitServer(app) { it.isRunning() && it.generation > server.generation }
            typeCode("dur")
            awaitCompletion("duringRestart")
        }
}
