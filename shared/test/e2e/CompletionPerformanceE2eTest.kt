package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LINES = 10_000
private const val TYPED = "functionname"

/** Cost of code completion: typing in a large PHP file, and a huge answer from the language server. */
@OptIn(ExperimentalTestApi::class)
class CompletionPerformanceE2eTest {
    private val body = (1..LINES).joinToString("\n") { "\$value$it = strlen('line $it of a large PHP file');" }
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            file("src/big.php", "<?php\n$body\n")
        }
    private val created = mutableListOf<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        created.forEach { it.delete() }
    }

    private fun servers(vararg options: String) = FakePhpServers(*options).chosen().also { created += it }

    private fun ComposeUiTest.openAtEnd(path: String) {
        openFromTree(path)
        onNodeWithTag(EditorTags.EDITOR).performClick()
        caretToEnd()
    }

    // One letter at a time, each frame rendered: in a PHP file each letter also asks for completion.
    private fun ComposeUiTest.typeWords() {
        for (c in TYPED) {
            typeCode("$c")
            renderFrame()
        }
    }

    @Test
    fun `typing in a 10,000-line PHP file sends the edits, not the file`() =
        runComposeUiTest {
            val servers = FakePhpServers().also { created += it }
            val app = launchWithProject(project, languageServers = servers.settings)
            // No server chosen yet: the same file, without a language server.
            openAtEnd("src/big.php")
            val without =
                measure(
                    "type ${TYPED.length} chars, 10k lines, no server",
                    budgetMillis = 4_000,
                ) { typeWords() }

            awaitTag(LanguageServerTags.LSP_CHOICE_QUICK).performClick()
            awaitServer(app) { it.isRunning() }
            onNodeWithTag(EditorTags.EDITOR).performClick()
            caretToEnd()
            app.client.network.recording = true
            val with =
                measure(
                    "type ${TYPED.length} chars, 10k lines, PHP server",
                    budgetMillis = 6_000,
                ) { typeWords() }
            app.client.network.recording = false
            val perKeyWithout = without.millis / TYPED.length
            val perKeyWith = with.millis / TYPED.length
            println("PERF per keystroke: $perKeyWithout ms without, $perKeyWith ms with")

            val sent =
                app.client.network
                    .snapshot()
                    .filter { it.direction == Direction.Sent }
            val changes = sent.filter { it.method == "DocChange" }
            assertTrue(changes.isNotEmpty(), "the edits are sent")
            val largest = changes.maxOf { it.size }
            println("PERF doc/change: ${changes.size} requests, largest $largest bytes (file ${body.length} chars)")
            assertTrue(largest < 1_024, "a keystroke sends $largest bytes")
            assertTrue(sent.none { it.method == "DocOpen" }, "the whole file is not sent again")
        }

    @Test
    fun `a 10,000-item answer opens a capped popup quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers("--completion-items", "10000").settings)
            openAtEnd("src/big.php")
            awaitServer(app) { it.isRunning() }
            typeCode("\n")
            // The first answer warms up the server's JVM: measured on the second one.
            typeCode("st")
            awaitCompletion("strlen")
            editorKeys { pressKey(Key.Escape) }
            typeCode(" ")
            app.client.network.recording = true
            measure("popup on a 10,000-item answer", budgetMillis = 5_000) {
                typeCode("g")
                awaitCompletion("generated_00001")
            }
            app.client.network.recording = false
            val popup = app.project.completion.popup!!
            assertEquals(200, popup.items.size, "the server sends at most 200 items")
            val answer =
                app.client.network.snapshot().last {
                    it.direction == Direction.Received &&
                        it.method == "LspCompletion"
                }
            println("PERF completion answer: ${answer.size} bytes")
            assertTrue(answer.size < 64 * 1024, "the answer is ${answer.size} bytes")
            // Virtualized: only the visible rows are composed.
            assertTrue(completionLabels().size <= 12, "${completionLabels().size} rows composed")
            measure("move the selection 100 rows", budgetMillis = 3_000) {
                repeat(100) {
                    editorKeys { pressKey(Key.DirectionDown) }
                    renderFrame()
                }
            }
            assertEquals(100, popup.selected)
            assertTrue(completionLabels().size <= 12, "${completionLabels().size} rows composed")
        }
}
