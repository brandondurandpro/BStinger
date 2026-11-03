package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PHP functions calling each other: `top()` calls `middle()`, which calls `leaf()` twice; the file calls `top()`. */
internal val CALLS_PHP =
    listOf(
        "<?php",
        "function leaf()",
        "{",
        "    return 1;",
        "}",
        "function middle()",
        "{",
        "    return leaf() + leaf();",
        "}",
        "function top()",
        "{",
        "    return middle();",
        "}",
        "top();",
        "",
    ).joinToString("\n")
internal const val LEAF_LINE = 1
internal const val TOP_LINE = 9
internal const val FUNCTION_NAME_COLUMN = 10

/** The call hierarchy (Ctrl+Alt+H) and the type hierarchy (Ctrl+H) of PHP code, from the language server. */
@OptIn(ExperimentalTestApi::class)
class PhpHierarchyE2eTest {
    private val project = codeInsightProject().apply { file("src/calls.php", CALLS_PHP) }
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    private fun E2eApp.activePath(): String? = project.editor.activeTab?.relativePath

    private fun ComposeUiTest.open(path: String = "src/calls.php", vararg options: String): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, languageServers = servers.settings)
        openFromTree(path)
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        return app
    }

    @Test
    fun `Ctrl+Alt+H lists the callers, Right expands them, Enter opens the call`() =
        runComposeUiTest {
            val app = open()
            clickAt(LEAF_LINE, FUNCTION_NAME_COLUMN)
            callHierarchy()
            awaitTag(EditorTags.HIERARCHY, timeout = SERVER_START_MS)
            assertEquals("Callers of leaf", textOf(EditorTags.HIERARCHY_TITLE))
            // The root, then middle, which calls it twice.
            awaitTag(EditorTags.hierarchyRow(1), timeout = SERVER_START_MS)
            assertTrue("middle" in textOf(EditorTags.hierarchyRow(1)))
            assertTrue("×2" in textOf(EditorTags.hierarchyRow(1)))
            editorKeys { pressKey(Key.DirectionDown) }
            onNodeWithTag(EditorTags.hierarchyRow(1)).assertIsSelected()
            editorKeys { pressKey(Key.DirectionRight) }
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            assertTrue("top" in textOf(EditorTags.hierarchyRow(2)))
            // Right again goes to the child, then expands it: the file calls top() at its top level.
            editorKeys { pressKey(Key.DirectionRight) }
            onNodeWithTag(EditorTags.hierarchyRow(2)).assertIsSelected()
            editorKeys { pressKey(Key.DirectionRight) }
            awaitTag(EditorTags.hierarchyRow(3), timeout = SERVER_START_MS)
            assertTrue("calls.php" in textOf(EditorTags.hierarchyRow(3)))
            // Enter on middle: the caret goes to its first call of leaf.
            editorKeys { pressKey(Key.DirectionUp) }
            editorKeys { pressKey(Key.Enter) }
            awaitNoTag(EditorTags.HIERARCHY)
            awaitCondition { app.caret() == TextPos(7, 11) }
        }

    @Test
    fun `the button shows the callees instead, and an arrow expands a row`() =
        runComposeUiTest {
            open()
            clickAt(TOP_LINE, FUNCTION_NAME_COLUMN)
            callHierarchy()
            awaitTag(EditorTags.HIERARCHY_SWITCH, timeout = SERVER_START_MS).performClick()
            awaitCondition { textOf(EditorTags.HIERARCHY_TITLE) == "Callees of top" }
            awaitTag(EditorTags.hierarchyRow(1), timeout = SERVER_START_MS)
            assertTrue("middle" in textOf(EditorTags.hierarchyRow(1)))
            onNodeWithTag(EditorTags.hierarchyToggle(1), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            assertTrue("leaf" in textOf(EditorTags.hierarchyRow(2)))
            // Collapsed again with its arrow.
            onNodeWithTag(EditorTags.hierarchyToggle(1), useUnmergedTree = true).performClick()
            awaitNoTag(EditorTags.hierarchyRow(2))
            // leaf calls nothing: no callees under it.
            onNodeWithTag(EditorTags.hierarchyToggle(1), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.hierarchyRow(2)).performClick()
            awaitNoTag(EditorTags.HIERARCHY)
        }

    @Test
    fun `Ctrl+H lists the classes implementing an interface, and a class's supertypes`() =
        runComposeUiTest {
            val app = open("src/Polite.php")
            // `interface Polite`
            clickAt(2, 12)
            typeHierarchy()
            awaitTag(EditorTags.HIERARCHY, timeout = SERVER_START_MS)
            assertEquals("Subtypes of Polite", textOf(EditorTags.HIERARCHY_TITLE))
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            val subtypes = listOf(textOf(EditorTags.hierarchyRow(1)), textOf(EditorTags.hierarchyRow(2)))
            assertTrue(subtypes.any { "Greeter" in it } && subtypes.any { "Shy" in it }, "$subtypes")
            // An interface that extends nothing.
            onNodeWithTag(EditorTags.HIERARCHY_SWITCH).performClick()
            awaitTag(EditorTags.HIERARCHY_EMPTY, timeout = SERVER_START_MS)
            assertEquals("No supertypes", textOf(EditorTags.HIERARCHY_EMPTY))
            onNodeWithTag(EditorTags.HIERARCHY_SWITCH).performClick()
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            onNodeWithTag(EditorTags.hierarchyRow(subtypes.indexOfFirst { "Shy" in it } + 1)).performClick()
            awaitCondition { app.activePath() == "src/Shy.php" }
            awaitCondition { app.caret() == TextPos(2, 6) }
        }

    @Test
    fun `a function calling itself can be expanded again and again`() =
        runComposeUiTest {
            project.file(
                "src/loop.php",
                listOf("<?php", "function recurse()", "{", "    recurse();", "}", "").joinToString("\n"),
            )
            open("src/loop.php")
            clickAt(1, FUNCTION_NAME_COLUMN)
            callHierarchy()
            awaitTag(EditorTags.hierarchyRow(1), timeout = SERVER_START_MS)
            repeat(DEPTH) { level ->
                editorKeys { pressKey(Key.DirectionRight) }
                editorKeys { pressKey(Key.DirectionRight) }
                awaitTag(EditorTags.hierarchyRow(level + 2), timeout = SERVER_START_MS)
            }
            assertTrue("recurse" in textOf(EditorTags.hierarchyRow(DEPTH + 1)))
            onNodeWithTag(EditorTags.hierarchyRow(DEPTH)).assertIsSelected()
            // Left collapses the row, then goes to its parent.
            editorKeys { pressKey(Key.DirectionLeft) }
            awaitNoTag(EditorTags.hierarchyRow(DEPTH + 1))
            editorKeys { pressKey(Key.DirectionLeft) }
            onNodeWithTag(EditorTags.hierarchyRow(DEPTH - 1)).assertIsSelected()
        }

    @Test
    fun `Escape and a click beside the tree close it`() =
        runComposeUiTest {
            open()
            clickAt(LEAF_LINE, FUNCTION_NAME_COLUMN)
            callHierarchy()
            awaitTag(EditorTags.HIERARCHY, timeout = SERVER_START_MS)
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.HIERARCHY)
            callHierarchy()
            awaitTag(EditorTags.HIERARCHY_SCRIM, timeout = SERVER_START_MS).performClick()
            awaitNoTag(EditorTags.HIERARCHY)
        }

    @Test
    fun `nothing at the caret, or a server without hierarchies, says so`() =
        runComposeUiTest {
            open("src/calls.php", "--without", "typeHierarchy")
            clickAt(0, 2)
            callHierarchy()
            awaitTag(EditorTags.NOTICE, timeout = SERVER_START_MS)
            assertEquals("No function or method at the caret", textOf(EditorTags.NOTICE))
            clickAt(LEAF_LINE, FUNCTION_NAME_COLUMN)
            typeHierarchy()
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.NOTICE) == "The language server has no type hierarchy" }
            awaitNoTag(EditorTags.HIERARCHY)
        }
}

private const val DEPTH = 3

/** Ctrl+Alt+H. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.callHierarchy() =
    editorKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.H) } } }

/** Ctrl+H. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.typeHierarchy() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.H) } }
