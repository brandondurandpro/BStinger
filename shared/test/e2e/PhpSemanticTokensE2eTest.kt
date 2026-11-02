package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A PHP file whose words the fake server classifies: an interface, a class, a deprecated method, a static one. */
internal val SHAPES_PHP =
    listOf(
        "<?php",
        "interface Shape",
        "{",
        "}",
        "class Circle implements Shape",
        "{",
        "    /**",
        "     * @deprecated Use area().",
        "     */",
        "    public function size(int \$scale)",
        "    {",
        "        return \$scale * 2;",
        "    }",
        "    public static function unit()",
        "    {",
        "        return new Circle();",
        "    }",
        "}",
        "\$circle = Circle::unit();",
        "echo \$circle->size(3);",
        "",
    ).joinToString("\n")

/** Where the words of [SHAPES_PHP] are. */
internal object Shapes {
    const val CLASS_LINE = 4
    const val CLASS_COLUMN = 6
    const val INTERFACE_COLUMN = 24
    const val SIZE_LINE = 9
    const val SIZE_COLUMN = 20
    const val PARAMETER_LINE = 11
    const val PARAMETER_COLUMN = 15
    const val UNIT_LINE = 13
    const val UNIT_COLUMN = 27
    const val STATIC_CALL_LINE = 18
    const val STATIC_CALL_COLUMN = 18
    const val CALL_LINE = 19
    const val CALL_COLUMN = 15
}

/** Semantic tokens in PHP (textDocument/semanticTokens): the code colored by what each word is. */
@OptIn(ExperimentalTestApi::class)
class PhpSemanticTokensE2eTest {
    private val project = codeInsightProject().apply { file("src/shapes.php", SHAPES_PHP) }
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun ComposeUiTest.open(vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree("src/shapes.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `classes, interfaces, parameters and calls get the colors of what they are`() =
        runComposeUiTest {
            open()
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.CLASS_LINE, Shapes.CLASS_COLUMN)?.color == IdeColors.SemanticType
            }
            // An interface is italic, a class is not.
            val shape = styleAt(Shapes.CLASS_LINE, Shapes.INTERFACE_COLUMN)
            assertEquals(IdeColors.SemanticType, shape?.color)
            assertEquals(FontStyle.Italic, shape?.fontStyle)
            assertNotEquals(FontStyle.Italic, styleAt(Shapes.CLASS_LINE, Shapes.CLASS_COLUMN)?.fontStyle)
            assertEquals(IdeColors.SemanticParameter, styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color)
            // The static method, declared and called: italic.
            val unit = styleAt(Shapes.UNIT_LINE, Shapes.UNIT_COLUMN)
            assertEquals(IdeColors.FunctionDeclaration, unit?.color)
            assertEquals(FontStyle.Italic, unit?.fontStyle)
            val staticCall = styleAt(Shapes.STATIC_CALL_LINE, Shapes.STATIC_CALL_COLUMN)
            assertEquals(IdeColors.SemanticCall, staticCall?.color)
            assertEquals(FontStyle.Italic, staticCall?.fontStyle)
        }

    @Test
    fun `a deprecated method is struck through where declared and where called`() =
        runComposeUiTest {
            open()
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.SIZE_LINE, Shapes.SIZE_COLUMN)?.textDecoration == TextDecoration.LineThrough
            }
            val call = styleAt(Shapes.CALL_LINE, Shapes.CALL_COLUMN)
            assertEquals(IdeColors.SemanticCall, call?.color)
            assertEquals(TextDecoration.LineThrough, call?.textDecoration)
            // Its parameter's type is not.
            assertNotEquals(
                TextDecoration.LineThrough,
                styleAt(Shapes.SIZE_LINE, Shapes.SIZE_COLUMN + 5)?.textDecoration,
            )
        }

    @Test
    fun `a line inserted above moves the colors with their words`() =
        runComposeUiTest {
            open()
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter
            }
            clickAt(0, 2)
            editorKeys { pressKey(Key.MoveEnd) }
            editorKeys { pressKey(Key.Enter) }
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.PARAMETER_LINE + 1, Shapes.PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter
            }
            awaitCondition { styleAt(Shapes.CLASS_LINE + 1, Shapes.CLASS_COLUMN)?.color == IdeColors.SemanticType }
        }

    @Test
    fun `a word being typed loses its color until the server tells what it is now`() =
        runComposeUiTest {
            open()
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter
            }
            // `$scale` becomes `$scalex`: no parameter of that name, a plain variable.
            clickBefore(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN + "\$scale".length)
            typeInEditor("x")
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color != IdeColors.SemanticParameter
            }
            // Back to `$scale`: a parameter again.
            editorKeys { pressKey(Key.Backspace) }
            awaitCondition(SERVER_START_MS) {
                styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color == IdeColors.SemanticParameter
            }
        }

    @Test
    fun `a server without semantic tokens leaves the colors of tree-sitter`() =
        runComposeUiTest {
            open("--without", "semanticTokens")
            // The usage counts are asked with the tokens: once shown, nothing more is coming.
            awaitTag(EditorTags.usageHint(Shapes.CLASS_LINE), unmerged = true, timeout = SERVER_START_MS)
            assertNotEquals(IdeColors.SemanticType, styleAt(Shapes.CLASS_LINE, Shapes.CLASS_COLUMN)?.color)
            assertNotEquals(IdeColors.SemanticParameter, styleAt(Shapes.PARAMETER_LINE, Shapes.PARAMETER_COLUMN)?.color)
        }
}
