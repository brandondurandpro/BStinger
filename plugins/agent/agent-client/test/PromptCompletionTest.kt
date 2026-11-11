package org.example.kotlineditor.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.example.kotlineditor.model.AgentSlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the prompt completes at its caret: a command at its start, a file after an `@`. */
class PromptCompletionTest {
    private fun at(text: String, caret: Int = text.length) = completingAt(TextFieldValue(text, TextRange(caret)))

    @Test
    fun `a slash completes a command at the start of the prompt only`() {
        assertEquals(Completing.Command("co", 0), at("/co"))
        assertEquals(Completing.Command("", 0), at("/"))
        assertNull(at("say /co"))
        assertNull(at(" /co"))
        assertNull(at("/co then", caret = 8))
    }

    @Test
    fun `an at sign completes a file anywhere, up to the caret`() {
        assertEquals(Completing.File("Main", 8), at("explain @Main"))
        assertEquals(Completing.File("Ma", 8), at("explain @Main", caret = 11))
        assertEquals(Completing.File("", 0), at("@"))
        assertNull(at("mail@example.com"))
        assertNull(at("explain @Main now"))
    }

    @Test
    fun `nothing completes over a selection or in an empty prompt`() {
        assertNull(completingAt(TextFieldValue("/co", TextRange(0, 3))))
        assertNull(at(""))
        assertNull(at("text", caret = 0))
    }

    @Test
    fun `the commands starting with the query come first`() {
        val commands = listOf("recount", "compact", "count").map { AgentSlashCommand(it, "") }
        assertEquals(listOf("compact", "count", "recount"), commandsMatching(commands, "co").map { it.name })
        assertEquals(listOf("recount", "compact", "count"), commandsMatching(commands, "").map { it.name })
    }

    @Test
    fun `a suggestion replaces the word typed, the caret after it`() {
        val value = TextFieldValue("explain @Ma please", TextRange(11))
        val word = checkNotNull(completingAt(value))
        val done = replaced(value, word, "")
        assertEquals("explain  please", done.text)
        assertEquals(TextRange(8), done.selection)
        val command = TextFieldValue("/co", TextRange(3))
        assertEquals("/count ", replaced(command, checkNotNull(completingAt(command)), "/count ").text)
    }
}
