package org.example.kotlineditor.e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The list of usages (Alt+F7, Ctrl+B on a declaration): the row under the mouse is highlighted, and the code of each
 * row is colored like in the editor, by the tree-sitter grammar of its file (the line lexer until it is loaded or
 * when there is none).
 *
 * The usages of `greet` are lines 2 and 3 of `src/index.php`: `echo $greeter->greet("world", 1);`.
 */
@OptIn(ExperimentalTestApi::class)
class UsagesListE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    // Lists the usages of `greet` from its declaration in Greeter.php.
    private fun ComposeUiTest.listUsages(grammars: GrammarStore? = TestGrammars.shared): E2eApp {
        val app = launchWithProject(project, grammars = grammars, languageServers = servers.settings)
        openFromTree("src/Greeter.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        clickAt(13, 22)
        editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
        awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
        return app
    }

    private fun E2eApp.hovered(): Int? = project.insight.locations?.hovered

    // The background of row [index], left of its text.
    private fun ComposeUiTest.backgroundOf(index: Int): Color {
        val pixels = onNodeWithTag(EditorTags.location(index)).captureToImage().toPixelMap()
        return pixels[1, pixels.height / 2]
    }

    private fun assertSameColor(expected: Color, actual: Color, what: String) {
        // The screenshot is 8 bits a channel: compare at that precision.
        val distance =
            maxOf(
                kotlin.math.abs(expected.red - actual.red),
                kotlin.math.abs(expected.green - actual.green),
                kotlin.math.abs(expected.blue - actual.blue),
            )
        assert(distance < 2f / 255) { "$what is $actual, not $expected" }
    }

    @Test
    fun `the row under the mouse is highlighted, the selected row keeps its color, and leaving clears it`() =
        runComposeUiTest {
            val app = listUsages()
            onNodeWithTag(EditorTags.location(0)).assertIsSelected()
            assertSameColor(IdeColors.Popup, backgroundOf(1), "a row neither selected nor hovered")
            onNodeWithTag(EditorTags.location(1)).performMouseInput { moveTo(center) }
            awaitCondition { app.hovered() == 1 }
            assertSameColor(IdeColors.Hover, backgroundOf(1), "the hovered row")
            assertSameColor(IdeColors.Selection, backgroundOf(0), "the selected row")
            // Hovering the selected row keeps the selection's color and frees the other row.
            onNodeWithTag(EditorTags.location(0)).performMouseInput { moveTo(center) }
            awaitCondition { app.hovered() == 0 }
            assertSameColor(IdeColors.Selection, backgroundOf(0), "the selected row under the mouse")
            assertSameColor(IdeColors.Popup, backgroundOf(1), "the row the mouse left")
            mouseOutOfEditor()
            awaitCondition { app.hovered() == -1 }
            // The arrows still move the selection, which the highlight does not follow.
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.location(1)).assertIsSelected()
            assertSameColor(IdeColors.Selection, backgroundOf(1), "the row selected with the arrows")
        }

    @Test
    fun `the code of each usage is colored by the PHP grammar`() =
        runComposeUiTest {
            listUsages()
            awaitPreviewColor(0, "greet", IdeColors.FunctionDeclaration)
            assertEquals(IdeColors.StringLiteral, previewColorOf(0, "\"world\""))
            assertEquals(IdeColors.Number, previewColorOf(0, "1"))
            assertEquals(IdeColors.Keyword, previewColorOf(0, "echo"))
            awaitPreviewColor(1, "greet", IdeColors.FunctionDeclaration)
            assertEquals(IdeColors.StringLiteral, previewColorOf(1, "\"again\""))
        }

    @Test
    fun `without the grammar the line lexer colors the code`() =
        runComposeUiTest {
            listUsages(grammars = null)
            awaitPreviewColor(0, "\"world\"", IdeColors.StringLiteral)
            assertEquals(IdeColors.Number, previewColorOf(0, "1"))
            // The lexer does not know calls: only the grammar colors them.
            assertEquals(IdeColors.Code, previewColorOf(0, "greet"))
        }
}
