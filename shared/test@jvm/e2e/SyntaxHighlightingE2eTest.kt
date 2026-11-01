package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tree-sitter highlighting: the grammar of a file's language is downloaded (from a [LocalGrammarRepository]) the
 * first time a file of that language opens, then colors it as it is edited. The line lexer colors the rest.
 *
 * Lines of `App.kt` ([SampleProject.APP_KT]): 4-5 a block comment, 7 `val greeting = "Hello"`,
 * 8 `println(greeting.shout())`. The lexer colors keywords, strings and comments but no call nor property: a colored
 * one shows the grammar's colors. Tests type no bracket nor quote: the e2e keyboard types `9` for Shift+9.
 */
@OptIn(ExperimentalTestApi::class)
class SyntaxHighlightingE2eTest {
    private val project = SampleProject.standard()
    private val repository = LocalGrammarRepository()

    @AfterTest
    fun cleanup() {
        repository.close()
        project.delete()
    }

    @Test
    fun `opening a Kotlin file colors it with its tree-sitter grammar`() =
        runComposeUiTest {
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            assertEquals(IdeColors.FunctionDeclaration, colorOf(8, "shout"))
            assertEquals(IdeColors.Code, colorOf(8, "greeting"))
            assertEquals(IdeColors.Keyword, colorOf(7, "val"))
            assertEquals(IdeColors.StringLiteral, colorOf(7, "\"Hello\""))
            assertEquals(IdeColors.Comment, colorOf(5, "of the demo */"))
            onNodeWithTag(Tags.STATUS_LANGUAGE).assertTextEquals("Kotlin")
            assertEquals(1, repository.jarsServed)
        }

    @Test
    fun `typing recolors the code being written`() =
        runComposeUiTest {
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            onNodeWithTag(Tags.EDITOR).performClick()
            pressInEditor(Key.MoveEnd, ctrl = true)
            typeInEditor("\nval answer = greeting.length - 42")
            awaitColor(10, "length", IdeColors.Property)
            assertEquals(IdeColors.Number, colorOf(10, "42"))
            assertEquals(IdeColors.Keyword, colorOf(10, "val"))
            assertEquals(IdeColors.FunctionDeclaration, colorOf(8, "println"), "the lines around keep their colors")
        }

    @Test
    fun `removing the start of a block comment uncolors the lines it covered, and undo brings them back`() =
        runComposeUiTest {
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            onNodeWithTag(Tags.EDITOR).performClick()
            pressInEditor(Key.MoveHome, ctrl = true)
            pressInEditor(Key.DirectionDown, times = 4)
            pressInEditor(Key.Delete, times = 2)
            awaitCondition { colorOf(5, "demo") != IdeColors.Comment }
            assertEquals(IdeColors.FunctionDeclaration, colorOf(8, "println"), "still colored by the grammar")
            // Each deleted character is an undo step.
            pressInEditor(Key.Z, ctrl = true, times = 2)
            awaitColor(5, "of the demo */", IdeColors.Comment)
            awaitColor(4, "/* Entry point", IdeColors.Comment)
        }

    @Test
    fun `an empty file is colored as soon as code is typed in it`() =
        runComposeUiTest {
            project.file("src/main/kotlin/Empty.kt", "")
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/Empty.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            typeInEditor("val n = names.size - 1")
            awaitColor(0, "size", IdeColors.Property)
            assertEquals(IdeColors.Number, colorOf(0, "1"))
            assertEquals(IdeColors.Keyword, colorOf(0, "val"))
        }

    @Test
    fun `each language has its own grammar, downloaded once for all its files`() =
        runComposeUiTest {
            project.file(
                "config.json",
                """
                {
                  "name": "demo",
                  "count": 3,
                  "ok": true
                }
                """.trimIndent(),
            )
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("config.json")
            // The JSON grammar's query colors keys as strings: the last pattern capturing a node wins.
            awaitColor(1, "\"demo\"", IdeColors.StringLiteral)
            assertEquals(IdeColors.StringLiteral, colorOf(1, "\"name\""))
            assertEquals(IdeColors.Number, colorOf(2, "3"))
            assertEquals(IdeColors.Keyword, colorOf(3, "true"))
            onNodeWithTag(Tags.STATUS_LANGUAGE).assertTextEquals("JSON")
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            openFromTree("src/main/kotlin/util/Strings.kt")
            awaitColor(2, "uppercase", IdeColors.FunctionDeclaration)
            assertEquals(2, repository.jarsServed, "one jar for JSON, one for Kotlin")
        }

    @Test
    fun `a language without grammar keeps the line lexer`() =
        runComposeUiTest {
            project.file("build.groovy", textOf("def x = \"a\" // note", "println(x)"))
            project.file("notes.txt", "Just some words")
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("build.groovy")
            awaitColor(0, "def", IdeColors.Keyword)
            assertEquals(IdeColors.StringLiteral, colorOf(0, "\"a\""))
            assertEquals(IdeColors.Comment, colorOf(0, "// note"))
            assertEquals(IdeColors.Code, colorOf(1, "println"))
            onNodeWithTag(Tags.STATUS_LANGUAGE).assertTextEquals("CLike")
            openFromTree("notes.txt")
            awaitCondition { colorOf(0, "Just") == IdeColors.Code }
            onNodeWithTag(Tags.STATUS_LANGUAGE).assertTextEquals("Plain")
            assertEquals(0, repository.jarsServed)
        }

    @Test
    fun `closing a file while its grammar downloads, then opening it again`() =
        runComposeUiTest {
            repository.latencyMs = SLOW_DOWNLOAD_MS
            launchWithProject(project, grammars = TestGrammars.store(repository))
            openFromTree("src/main/kotlin/App.kt")
            onNodeWithTag(Tags.tabClose("src/main/kotlin/App.kt")).performClick()
            awaitNoTag(Tags.tab("src/main/kotlin/App.kt"))
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(8, "println", IdeColors.FunctionDeclaration)
            assertEquals(1, repository.jarsServed, "the download goes on for the file opened again")
        }

    @Test
    fun `without grammars the IDE colors every file with the line lexer`() =
        runComposeUiTest {
            launchWithProject(project, grammars = null)
            openFromTree("src/main/kotlin/App.kt")
            awaitColor(7, "val", IdeColors.Keyword)
            assertEquals(IdeColors.Code, colorOf(8, "println"))
            onNodeWithTag(Tags.STATUS_LANGUAGE).assertTextEquals("Kotlin")
        }

    private companion object {
        const val SLOW_DOWNLOAD_MS = 1_000L
    }
}
