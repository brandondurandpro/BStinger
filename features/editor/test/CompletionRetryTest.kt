package org.example.kotlineditor.unit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.CompletionItem
import org.example.kotlineditor.model.CompletionKind
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.state.CompletionState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.moveEnd
import org.example.kotlineditor.syntax.Language
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A completion asked while the language server still loads the project is asked again, instead of showing nothing. */
class CompletionRetryTest {
    private val item = CompletionItem("value", CompletionKind.Variable, null, "value")
    private val answer = CompletionResult(0, listOf(item), incomplete = false, ready = true)

    private fun typedIn(notReadyAnswers: Int, check: suspend (CompletionState, AtomicInteger) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val asked = AtomicInteger()
            val state =
                CompletionState(scope) { _, _ ->
                    if (asked.incrementAndGet() <= notReadyAnswers) CompletionResult.NotReady else answer
                }
            val document = Document(listOf("va"), Language.Plain)
            document.moveEnd()
            state.typed("a.ts", document, 'a')
            check(state, asked)
            scope.cancel()
        }

    private suspend fun awaitPopup(state: CompletionState) {
        repeat(AWAIT_STEPS) { if (state.popup == null) delay(STEP_MS) }
    }

    @Test
    fun `a server not ready at first is asked again until it answers`() =
        typedIn(notReadyAnswers = 2) { state, asked ->
            awaitPopup(state)
            assertNotNull(state.popup)
            assertEquals(3, asked.get())
        }

    @Test
    fun `a server that never gets ready is not asked forever`() =
        typedIn(notReadyAnswers = Int.MAX_VALUE) { state, asked ->
            delay(NEVER_READY_WAIT_MS)
            assertNull(state.popup)
            assertEquals(9, asked.get())
        }

    private companion object {
        const val AWAIT_STEPS = 60
        const val STEP_MS = 50L
        const val NEVER_READY_WAIT_MS = 5_000L
    }
}
