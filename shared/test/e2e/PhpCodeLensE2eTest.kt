package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// `interface Polite` on line 2 of src/Polite.php: Greeter and Shy implement it.
private const val INTERFACE_LINE = 2

// A function without a docblock on line 2: the server's lens adds one.
private val HELPERS_PHP =
    listOf(
        "<?php",
        "",
        "function shout(\$text)",
        "{",
        "    return \$text;",
        "}",
        "",
    ).joinToString("\n")
private const val HELPER_LINE = 2

/** Code lenses in PHP (textDocument/codeLens): shown after the usage hint, listing places or running a command. */
@OptIn(ExperimentalTestApi::class)
class PhpCodeLensE2eTest {
    private val project = codeInsightProject().apply { file("src/helpers.php", HELPERS_PHP) }
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun E2eApp.activePath(): String? = project.editor.activeTab?.relativePath

    private fun E2eApp.lines(): List<String> =
        project.editor.activeTab
            ?.document
            ?.lines
            ?.toList()
            .orEmpty()

    private fun ComposeUiTest.open(path: String, vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    private fun ComposeUiTest.awaitLens(line: Int, at: Int = 0) =
        awaitTag(EditorTags.codeLens(line, at), timeout = SERVER_START_MS, unmerged = true)

    @Test
    fun `an interface shows how many classes implement it, after its usages, and a click lists them`() =
        runComposeUiTest {
            val app = open("src/Polite.php")
            awaitLens(INTERFACE_LINE)
            awaitTag(EditorTags.usageHint(INTERFACE_LINE), timeout = SERVER_START_MS, unmerged = true)
            assertEquals("2 implementations", textOf(EditorTags.codeLens(INTERFACE_LINE, 0)))
            val usages =
                onNodeWithTag(
                    EditorTags.usageHint(INTERFACE_LINE),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
            val lens =
                onNodeWithTag(
                    EditorTags.codeLens(INTERFACE_LINE, 0),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
            assertTrue(lens.boundsInRoot.left > usages.boundsInRoot.right, "the lens comes after the usage hint")
            onNodeWithTag(EditorTags.codeLens(INTERFACE_LINE, 0), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.LOCATIONS)
            assertEquals("2 implementations", textOf(EditorTags.LOCATIONS_TITLE))
            onNodeWithTag(EditorTags.location(0)).performClick()
            awaitCondition { app.activePath() == "src/Greeter.php" || app.activePath() == "src/Shy.php" }
        }

    @Test
    fun `a lens the server resolves runs its command, which edits the file, and then goes away`() =
        runComposeUiTest {
            val app = open("src/helpers.php")
            awaitLens(HELPER_LINE)
            assertEquals("Add docblock", textOf(EditorTags.codeLens(HELPER_LINE, 0)))
            onNodeWithTag(EditorTags.codeLens(HELPER_LINE, 0), useUnmergedTree = true).performClick()
            awaitCondition { app.lines().getOrNull(HELPER_LINE) == "/**" }
            assertEquals(listOf("/**", " * TODO", " */", "function shout(\$text)"), app.lines().subList(2, 6))
            // Documented now: the server offers no lens any more.
            awaitNoTag(EditorTags.codeLens(HELPER_LINE + 3, 0), timeout = SERVER_START_MS, unmerged = true)
            awaitNoTag(EditorTags.codeLens(HELPER_LINE, 0), unmerged = true)
        }

    @Test
    fun `the pointer is a hand over a lens`() =
        runComposeUiTest {
            val app = open("src/helpers.php")
            awaitLens(HELPER_LINE)
            onNodeWithTag(EditorTags.codeLens(HELPER_LINE, 0), useUnmergedTree = true)
                .performMouseInput { moveTo(center) }
            awaitCondition {
                app.project.insight.hintUnderMouse ===
                    app.project.editor.activeTab
                        ?.document
            }
            hoverAt(HELPER_LINE, 3)
            awaitCondition { app.project.insight.hintUnderMouse == null }
        }

    @Test
    fun `a lens follows the lines typed above it`() =
        runComposeUiTest {
            open("src/Polite.php")
            awaitLens(INTERFACE_LINE)
            clickAt(0, 0)
            editorKeys { pressKey(Key.MoveEnd) }
            editorKeys { pressKey(Key.Enter) }
            awaitLens(INTERFACE_LINE + 1)
            assertEquals("2 implementations", textOf(EditorTags.codeLens(INTERFACE_LINE + 1, 0)))
        }

    @Test
    fun `without code lenses from the server, the usage hint stands alone`() =
        runComposeUiTest {
            val app = open("src/Polite.php", "--without", "codeLens")
            awaitTag(EditorTags.usageHint(INTERFACE_LINE), timeout = SERVER_START_MS, unmerged = true)
            // The lenses are asked before the semantic tokens.
            val document =
                app.project.editor.activeTab
                    ?.document
            awaitCondition(SERVER_START_MS) {
                val insight = document?.let { app.project.insight.insightOf(it) }
                insight?.semanticTokens?.isNotEmpty() == true
            }
            awaitNoTag(EditorTags.codeLens(INTERFACE_LINE, 0), unmerged = true)
        }
}
