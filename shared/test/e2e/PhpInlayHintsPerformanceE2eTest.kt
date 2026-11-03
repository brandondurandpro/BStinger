package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CALLS = 3_000
private const val ARGUMENTS = 3

// `add(1, 2, 3);` after the declaration: the arguments of the call on line [index].
private const val FIRST_CALL_LINE = 2
private const val FIRST_ARGUMENT_COLUMN = 4

/** Inlay hints drawn in a PHP file of 3,000 calls (9,000 hints): arriving, typing above them, jumping to the end. */
@OptIn(ExperimentalTestApi::class)
class PhpInlayHintsPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val calls = (1..CALLS).map { "add($it, $it, $it);" }
            val declaration = "function add(int \$first, int \$second, int \$third) { return \$first; }"
            file("src/big.php", (listOf("<?php", declaration) + calls + "").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `9,000 hints arrive, follow typing and scroll quickly`() =
        runComposeUiTest {
            val preferences = InMemoryPreferenceStore(mapOf(PreferenceKeys.INLAY_HINTS to InlayHintsMode.Inline.name))
            val app = launchWithProject(project, preferences, languageServers = servers.settings)
            openFromTree("src/big.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            measure("${CALLS * ARGUMENTS} inlay hints from the server", budgetMillis = 15_000) {
                awaitCondition(SERVER_START_MS) { document?.inlays?.size == CALLS }
                awaitTag(EditorTags.inlayHint(FIRST_CALL_LINE, FIRST_ARGUMENT_COLUMN), unmerged = true)
                renderFrame()
            }
            clickAt(0, 0)
            measure("a line typed above ${CALLS * ARGUMENTS} hints", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.MoveEnd) }
                editorKeys { pressKey(Key.Enter) }
                awaitTag(EditorTags.inlayHint(FIRST_CALL_LINE + 1, FIRST_ARGUMENT_COLUMN), unmerged = true)
                renderFrame()
            }
            // After the line inserted: the last call is one line further.
            val lastCall = FIRST_CALL_LINE + CALLS
            measure("jump to the end with ${CALLS * ARGUMENTS} hints", budgetMillis = 3_000) {
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
                awaitTag(EditorTags.inlayHint(lastCall, FIRST_ARGUMENT_COLUMN), unmerged = true)
                renderFrame()
            }
            assertEquals(CALLS, document?.inlays?.size)
        }
}
