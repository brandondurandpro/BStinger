package org.example.kotlineditor.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `ask` block a member ends its message with: what it asks, and what is not a question. */
class MemberQuestionsTest {
    private fun block(json: String) = "I need to know.\n```ask\n$json\n```"

    @Test
    fun `the questions of the block and the text around it are read`() {
        val ask = askOf(block("""{"questions": [{"question": "Which database?", "options": ["SQLite", "Postgres"]}, {"question": "Why?"}]}"""))
        assertEquals("I need to know.", ask?.message)
        assertEquals(
            listOf(MemberQuestion("Which database?", listOf("SQLite", "Postgres")), MemberQuestion("Why?", emptyList())),
            ask?.questions,
        )
    }

    @Test
    fun `at most six questions are kept, and empty or repeated choices are dropped`() {
        val many = (1..9).joinToString(",") { """{"question": "Q$it", "options": ["a", "a", " ", "b"]}""" }
        val ask = askOf(block("""{"questions": [$many]}"""))
        assertEquals(MAX_QUESTIONS, ask?.questions?.size)
        assertEquals(listOf("a", "b"), ask?.questions?.firstOrNull()?.choices)
    }

    @Test
    fun `a message without a readable block asks nothing`() {
        assertNull(askOf("Done, nothing to ask."))
        assertNull(askOf(block("not json")))
        assertNull(askOf(block("""{"questions": []}""")))
        assertNull(askOf(block("""{"questions": [{"options": ["a"]}]}""")))
        assertNull(askOf("```ask\n{\"questions\": [{\"question\": \"Q\"}]}"))
    }

    @Test
    fun `the last block counts`() {
        val first = block("""{"questions": [{"question": "First?"}]}""")
        val ask = askOf(first + "\n" + block("""{"questions": [{"question": "Second?"}]}"""))
        assertEquals(listOf("Second?"), ask?.questions?.map { it.text })
    }
}
