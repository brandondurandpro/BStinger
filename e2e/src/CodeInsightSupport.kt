package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import org.example.kotlineditor.ui.EditorTags

/** A PHP project whose code the fake language server understands: a documented class and its use. */
fun codeInsightProject(): SampleProject =
    SampleProject().apply {
        file("composer.json", "{}\n")
        file(
            "src/Greeter.php",
            """
            <?php

            /**
             * Greets people politely.
             */
            class Greeter implements Polite
            {
                /**
                 * Says hello to someone.
                 *
                 * @param string ${'$'}name Who to greet.
                 * @return string The greeting.
                 */
                public function greet(string ${'$'}name, int ${'$'}times): string
                {
                    return "Hello " . strtoupper(${'$'}name);
                }
            }
            """.trimIndent() + "\n",
        )
        file(
            "src/index.php",
            """
            <?php
            ${'$'}greeter = new Greeter();
            echo ${'$'}greeter->greet("world", 1);
            echo ${'$'}greeter->greet("again", 2);
            """.trimIndent() + "\n",
        )
        file("src/Polite.php", phpFile("interface Polite"))
        file("src/Shy.php", phpFile("class Shy implements Polite"))
        file("README.md", "# Greetings\n")
    }

// A PHP file declaring an empty type.
private fun phpFile(declaration: String): String = listOf("<?php", "", declaration, "{", "}", "").joinToString("\n")

// Where the character at [column] of the editor line [line] is, in the line text node's coordinates.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.cellOffset(line: Int, column: Int): Offset {
    val node = onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).fetchSemanticsNode()
    val text = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
    val charWidth = node.size.width.toFloat() / text.length.coerceAtLeast(1)
    return Offset((column + 0.5f) * charWidth, node.size.height / 2f)
}

/** Moves the mouse over the character at [column] of [line] of the editor, without clicking. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.hoverAt(line: Int, column: Int) {
    val offset = cellOffset(line, column)
    onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).performMouseInput { moveTo(offset) }
    waitForIdle()
}

/** Moves the mouse out of the editor. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.mouseOutOfEditor() {
    onNodeWithTag(EditorTags.EDITOR).performMouseInput { moveTo(Offset(-50f, -50f)) }
    waitForIdle()
}

/** Ctrl+click on the character at [column] of [line]: the mouse moves there with Ctrl held, then clicks. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.ctrlClickAt(line: Int, column: Int) {
    val offset = cellOffset(line, column)
    onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).performMultiModalInput {
        key { keyDown(Key.CtrlLeft) }
        mouse { moveTo(offset) }
        mouse { click(offset) }
        key { keyUp(Key.CtrlLeft) }
    }
    waitForIdle()
}

/** Puts the caret on the character at [column] of [line] with a click. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.clickAt(line: Int, column: Int) {
    val offset = cellOffset(line, column)
    onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).performMouseInput { click(offset) }
    waitForIdle()
}

/** The mouse moves over the character at [column] of [line] with Ctrl held, without clicking. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.ctrlHoverAt(line: Int, column: Int) {
    val offset = cellOffset(line, column)
    onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).performMultiModalInput {
        key { keyDown(Key.CtrlLeft) }
        mouse { moveTo(Offset(offset.x - 1f, offset.y)) }
        mouse { moveTo(offset) }
    }
    waitForIdle()
}
