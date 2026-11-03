package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.PreferenceStore
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.LanguageServerTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// In src/index.php: `echo $greeter->greet("world", 1);`, its arguments at columns 21 and 30.
private const val CALL_LINE = 2
private const val FIRST_ARGUMENT = 21
private const val SECOND_ARGUMENT = 30

// In src/index.php: `$greeter = new Greeter();`, the type drawn after the variable, before column 8.
private const val CREATED_LINE = 1
private const val CREATED_VARIABLE_END = 8

// A class, then a variable created from it: the server names the class after its closing brace (`} class Shop`).
private val SHOP_PHP = listOf("<?php", "class Shop", "{", "}", "\$shop = new Shop();", "").joinToString("\n")
private const val SHOP_CLASS_END_LINE = 3
private const val SHOP_CREATED_LINE = 4

// A function declared, then called: its usage count on line 1, the hint of its argument on line 2.
private val TWICE_PHP = listOf("<?php", "function twice(int \$n) { return \$n; }", "twice(2);", "").joinToString("\n")
private const val TWICE_ARGUMENT = 6

// Like a legacy report: several functions, each one a search of its usages, then a call with an argument.
private val REPORT_PHP =
    (listOf("<?php") + (1..6).map { "function cell$it(int \$n) { return \$n; }" } + listOf("cell1(2);", ""))
        .joinToString("\n")
private const val REPORT_CALL_LINE = 7
private const val REPORT_ARGUMENT = 6

/**
 * Inlay hints in PHP (`name:` before an argument, `: Type` after a variable, textDocument/inlayHint): off, in a
 * popup, or in the code.
 */
@OptIn(ExperimentalTestApi::class)
class PhpInlayHintsE2eTest {
    private val project = codeInsightProject()
    private val created = ArrayList<FakePhpServers>()
    private val dir = Files.createTempDirectory("kotlin-editor-inlays").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
        dir.deleteRecursively()
    }

    private fun E2eApp.caret(): TextPos? =
        project.editor.activeTab
            ?.document
            ?.caret

    private fun ComposeUiTest.open(
        vararg options: String,
        preferences: PreferenceStore = FilePreferenceStore(dir.resolve("preferences.properties")),
    ): E2eApp {
        val servers = FakePhpServers(*options).chosen().also { created += it }
        val app = launchWithProject(project, preferences, languageServers = servers.settings)
        openFromTree("src/index.php")
        val server = awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        // Language Servers window: the choice of the mode.
        awaitTag(LanguageServerTags.statusLanguageServer(server.id)).performClick()
        awaitTag(LanguageServerTags.LANGUAGE_SERVERS)
        return app
    }

    private fun ComposeUiTest.choose(mode: InlayHintsMode) {
        onNodeWithTag(LanguageServerTags.inlayHintsMode(mode.name)).performClick()
        onNodeWithTag(LanguageServerTags.inlayHintsMode(mode.name)).assertIsSelected()
    }

    private fun hint(column: Int) = EditorTags.inlayHint(CALL_LINE, column)

    @Test
    fun `inline, the parameter names are drawn before the arguments`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Inline)
            awaitTag(hint(FIRST_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
            onNodeWithTag(hint(FIRST_ARGUMENT), useUnmergedTree = true).assertTextEquals("name: ")
            onNodeWithTag(hint(SECOND_ARGUMENT), useUnmergedTree = true).assertTextEquals("times: ")
            // A declaration is not a call: no hint on its line.
            awaitNoTag(EditorTags.inlayHint(1, 22), unmerged = true)
            // A server that gives them needs no explanation.
            awaitNoTag(LanguageServerTags.INLAY_HINTS_NOTE)
        }

    @Test
    fun `hints come from a server that reports each search of references as a progress, like phpactor`() =
        runComposeUiTest {
            // Each usage count is a search, each search a progress: the server goes indexing, then running, again
            // and again. Restarting the counts at each change never finished them, and the hints, asked after the
            // counts, never came.
            project.file("src/report.php", REPORT_PHP)
            open("--progress-on-references", "400")
            choose(InlayHintsMode.Inline)
            openFromTree("src/report.php")
            val argument = EditorTags.inlayHint(REPORT_CALL_LINE, REPORT_ARGUMENT)
            awaitTag(argument, unmerged = true, timeout = SERVER_START_MS)
            onNodeWithTag(argument, useUnmergedTree = true).assertTextEquals("n: ")
        }

    @Test
    fun `inline, the type of a variable is drawn after its name`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Inline)
            val type = EditorTags.inlayHint(CREATED_LINE, CREATED_VARIABLE_END)
            awaitTag(type, unmerged = true, timeout = SERVER_START_MS)
            onNodeWithTag(type, useUnmergedTree = true).assertTextEquals(": Greeter")
            onNodeWithTag(EditorTags.lineText(CREATED_LINE), useUnmergedTree = true)
                .assertTextEquals("\$greeter = new Greeter();")
        }

    @Test
    fun `in a popup, the line of an assignment is shown with the type of its variable`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Popup)
            clickAt(CREATED_LINE, 2)
            awaitTag(EditorTags.INLAY_HINTS_POPUP, timeout = SERVER_START_MS)
                .assertTextEquals("\$greeter: Greeter = new Greeter();")
        }

    @Test
    fun `the name of the block a brace closes is not drawn after it, as in IntelliJ`() =
        runComposeUiTest {
            project.file("src/shop.php", SHOP_PHP)
            open()
            choose(InlayHintsMode.Inline)
            openFromTree("src/shop.php")
            // Both hints come in the same answer: once the variable's type shows, the class's would have too.
            awaitTag(
                EditorTags.inlayHint(SHOP_CREATED_LINE, "\$shop".length),
                unmerged = true,
                timeout = SERVER_START_MS,
            )
            awaitNoTag(EditorTags.inlayHint(SHOP_CLASS_END_LINE, 1), unmerged = true)
        }

    @Test
    fun `inline, a click on a hint or just after it puts the caret before its argument`() =
        runComposeUiTest {
            val app = open()
            choose(InlayHintsMode.Inline)
            val hint = awaitTag(hint(SECOND_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
            hint.performMouseInput { click(center) }
            awaitCondition { app.caret() == TextPos(CALL_LINE, SECOND_ARGUMENT) }
            clickAt(CALL_LINE, 2)
            // Just right of the hint: the boundary before `1`.
            hint.performMouseInput { click(Offset(width + 2f, centerY)) }
            awaitCondition { app.caret() == TextPos(CALL_LINE, SECOND_ARGUMENT) }
            // The arrows go over the text, not over the hint.
            editorKeys { pressKey(Key.DirectionLeft) }
            awaitCondition { app.caret() == TextPos(CALL_LINE, SECOND_ARGUMENT - 1) }
            editorKeys { pressKey(Key.DirectionRight) }
            editorKeys { pressKey(Key.DirectionRight) }
            awaitCondition { app.caret() == TextPos(CALL_LINE, SECOND_ARGUMENT + 1) }
        }

    @Test
    fun `inline, the hints follow the text typed before them`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Inline)
            awaitTag(hint(SECOND_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
            // In "world", drawn after its 6-cell hint: the second argument moves 2 columns right.
            clickAt(CALL_LINE, FIRST_ARGUMENT + "name: ".length + 2)
            typeInEditor("ab")
            awaitTag(hint(SECOND_ARGUMENT + 2), unmerged = true)
            awaitNoTag(hint(SECOND_ARGUMENT), unmerged = true)
            onNodeWithTag(hint(SECOND_ARGUMENT + 2), useUnmergedTree = true).assertTextEquals("times: ")
        }

    @Test
    fun `in a popup, the line of the caret is shown with its hints above it, the code does not move`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Popup)
            clickAt(CALL_LINE, 2)
            awaitTag(EditorTags.INLAY_HINTS_POPUP, timeout = SERVER_START_MS)
                .assertTextEquals("echo \$greeter->greet(name: \"world\", times: 1);")
            awaitNoTag(hint(FIRST_ARGUMENT), unmerged = true)
            // A line without hints shows none.
            clickAt(0, 1)
            awaitNoTag(EditorTags.INLAY_HINTS_POPUP)
        }

    @Test
    fun `off, no hint is shown anywhere`() =
        runComposeUiTest {
            open()
            choose(InlayHintsMode.Inline)
            awaitTag(hint(FIRST_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
            choose(InlayHintsMode.Off)
            awaitNoTag(hint(FIRST_ARGUMENT), unmerged = true)
            clickAt(CALL_LINE, 2)
            awaitNoTag(EditorTags.INLAY_HINTS_POPUP)
        }

    @Test
    fun `a server without inlay hints shows none, in any mode, and the Language Servers window says why`() =
        runComposeUiTest {
            project.file("src/twice.php", TWICE_PHP)
            open("--without", "inlayHint")
            choose(InlayHintsMode.Inline)
            awaitTag(LanguageServerTags.INLAY_HINTS_NOTE).assertTextEquals(FakePhpServers.WITHOUT_INLAY_HINTS)
            openFromTree("src/twice.php")
            // The hints are asked with the usage counts: once those are shown, nothing more is coming.
            awaitTag(EditorTags.usageHint(1), unmerged = true, timeout = SERVER_START_MS)
            awaitNoTag(EditorTags.inlayHint(2, TWICE_ARGUMENT), unmerged = true)
            choose(InlayHintsMode.Popup)
            clickAt(2, 1)
            awaitNoTag(EditorTags.INLAY_HINTS_POPUP)
        }

    @Test
    fun `the same file shows the hint of its call with a server that has them`() =
        runComposeUiTest {
            project.file("src/twice.php", TWICE_PHP)
            open()
            choose(InlayHintsMode.Inline)
            openFromTree("src/twice.php")
            awaitTag(EditorTags.inlayHint(2, TWICE_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
            assertEquals("n: ", textOf(EditorTags.inlayHint(2, TWICE_ARGUMENT)))
        }

    @Test
    fun `the mode chosen is kept for the next session`() {
        val file = dir.resolve("kept.properties")
        runComposeUiTest {
            open(preferences = FilePreferenceStore(file))
            choose(InlayHintsMode.Inline)
            awaitCondition { FilePreferenceStore(file).load()[PreferenceKeys.INLAY_HINTS] == "Inline" }
        }
        runComposeUiTest {
            val app = open(preferences = FilePreferenceStore(file))
            assertEquals(InlayHintsMode.Inline, app.project.inlayHintsMode)
            awaitTag(hint(FIRST_ARGUMENT), unmerged = true, timeout = SERVER_START_MS)
        }
    }
}
