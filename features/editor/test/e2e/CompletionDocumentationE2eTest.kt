package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

// The documentation the fake server gives the built-in functions.
private val BUILT_IN_DOCUMENTATION =
    mapOf(
        "strlen" to "Get string length",
        "strtoupper" to "Make a string uppercase",
        "str_replace" to "Replace all occurrences",
    )

/** The documentation of the selected completion item, next to the list, from completionItem/resolve (see #97). */
@OptIn(ExperimentalTestApi::class)
class CompletionDocumentationE2eTest {
    private val project =
        FakePhpServers.project().apply {
            val documented =
                listOf(
                    "<?php",
                    "/**",
                    " * Adds two numbers.",
                    " */",
                    "function addNumbers(int \$a, int \$b) {}",
                    "",
                )
            file("src/math.php", documented.joinToString("\n"))
        }
    private val created = mutableListOf<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun servers(vararg options: String) = FakePhpServers(*options).chosen().also { created += it }

    private fun ComposeUiTest.openPhp(vararg options: String): E2eApp {
        val app = launchWithProject(project, languageServers = servers(*options).settings)
        openFromTree("src/math.php")
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        onNodeWithTag(EditorTags.EDITOR).performClick()
        caretToEnd()
        return app
    }

    private fun E2eApp.selectedLabel(): String? =
        project.completion.popup
            ?.selectedItem
            ?.label

    private fun ComposeUiTest.documentation(): String =
        if (onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(EditorTags.COMPLETION_DOCUMENTATION),
            ).fetchSemanticsNodes().isEmpty()
        ) {
            ""
        } else {
            textOf(EditorTags.COMPLETION_DOCUMENTATION)
        }

    // Waits for the documentation of the selected built-in to show.
    private fun ComposeUiTest.awaitDocumentationOfSelected(app: E2eApp): String {
        var shown = ""
        awaitCondition(SERVER_START_MS) {
            val expected = BUILT_IN_DOCUMENTATION[app.selectedLabel()]
            shown = documentation()
            expected != null && expected in shown
        }
        return shown
    }

    @Test
    fun `the documentation of the selected item shows next to the list`() =
        runComposeUiTest {
            openPhp()
            typeCode("strl")
            awaitCompletion("strlen")
            awaitTag(EditorTags.COMPLETION_DOCUMENTATION, timeout = SERVER_START_MS, unmerged = true)
            awaitCondition(SERVER_START_MS) { "Get string length" in documentation() }
            // The signature is part of it, in a code block.
            assertTrue("function strlen(string" in documentation(), documentation())
        }

    @Test
    fun `the arrows show the documentation of the item they select, never the previous one`() =
        runComposeUiTest {
            val app = openPhp()
            typeCode("str")
            awaitCompletion("strtoupper")
            val first = app.selectedLabel()
            awaitDocumentationOfSelected(app)
            editorKeys { pressKey(Key.DirectionDown) }
            awaitCondition { app.selectedLabel() != first }
            val shown = awaitDocumentationOfSelected(app)
            val previous = BUILT_IN_DOCUMENTATION[first]
            assertTrue(previous == null || previous !in shown, shown)
            // Back up: shown at once, from what was already resolved.
            editorKeys { pressKey(Key.DirectionUp) }
            awaitDocumentationOfSelected(app)
        }

    @Test
    fun `a function of the project shows its docblock`() =
        runComposeUiTest {
            openPhp()
            typeCode("addN")
            awaitCompletion("addNumbers")
            awaitCondition(SERVER_START_MS) { "Adds two numbers." in documentation() }
        }

    @Test
    fun `an item without documentation shows none`() =
        runComposeUiTest {
            val app = openPhp("--completion-items", "3")
            typeCode("gener")
            awaitCompletion("generated_00001")
            // Asked, answered without documentation: nothing is shown.
            awaitCondition(SERVER_START_MS) { !app.project.completion.describing }
            awaitNoTag(EditorTags.COMPLETION_DOCUMENTATION)
        }

    @Test
    fun `a server that cannot resolve items shows no documentation and does not ask`() =
        runComposeUiTest {
            val app = openPhp("--without", "completionResolve")
            typeCode("strl")
            awaitCompletion("strlen")
            assertTrue(!app.project.completion.describing)
            awaitNoTag(EditorTags.COMPLETION_DOCUMENTATION)
        }

    @Test
    fun `Escape closes the documentation with the list`() =
        runComposeUiTest {
            openPhp()
            typeCode("strl")
            awaitCondition(SERVER_START_MS) { "Get string length" in documentation() }
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.COMPLETION)
            awaitNoTag(EditorTags.COMPLETION_DOCUMENTATION)
        }
}
