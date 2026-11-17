package org.example.kotlineditor.unit

import org.example.kotlineditor.model.TeamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamTextTest {
    private fun lines(vararg parts: String): String = parts.joinToString("\n")

    @Test
    fun `a prompt without the heading is what the user typed`() {
        assertEquals("fix the build", TeamText.typed("fix the build"))
        assertEquals("", TeamText.typed(""))
    }

    @Test
    fun `the text of the user is what follows the last heading of a wrapped prompt`() {
        val first = TeamText.wrap("You are Lead, the manager of the team \"Crew\".\n\n# Your team\n- Backend", "fix\nthe build")
        assertEquals("fix\nthe build", TeamText.typed(first))
        assertEquals("again", TeamText.typed(TeamText.wrap("", "again")))
    }

    @Test
    fun `a heading the user typed in a wrapped prompt is the last one`() {
        val prompt = TeamText.wrap("", lines("see below", TeamText.REQUEST_HEADING, "the rest"))
        assertEquals("the rest", TeamText.typed(prompt))
    }

    @Test
    fun `a heading with spaces around it counts, one inside a line does not`() {
        assertEquals("go", TeamText.typed(lines("intro", "  ${TeamText.REQUEST_HEADING}  ", "", "go")))
        val inside = "read ${TeamText.REQUEST_HEADING} and go"
        assertEquals(inside, TeamText.typed(inside))
    }

    @Test
    fun `the prompts the harness writes on its own are no message of the user`() {
        val relay = TeamText.wrap("${TeamText.RELAY_OPENING} Their reports are below.", "## Backend (done)\nbuilt")
        val refusal = TeamText.wrap("${TeamText.REFUSAL_OPENING} The last section says why.", "Not JSON.")
        assertNull(TeamText.typed(relay))
        assertNull(TeamText.typed(refusal))
    }

    @Test
    fun `a user who types the words of the harness is still the user`() {
        // Typed as a later prompt, the words come after the heading, not before it.
        val typed = "${TeamText.RELAY_OPENING} really"
        assertEquals(typed, TeamText.typed(TeamText.wrap("", typed)))
        // Sent as typed (no heading at all), nothing is taken for a prompt of the harness.
        assertEquals(typed, TeamText.typed(typed))
    }

    @Test
    fun `an answer without a block is left as it is`() {
        val answer = "  Done.\n\n```kotlin\nval a = 1\n```\n"
        assertEquals(answer, TeamText.withoutBlocks(answer))
    }

    @Test
    fun `the block of a delegation is left out, the text around it kept`() {
        val block = lines(TeamText.BLOCK_OPENING, """{"delegate": []}""", TeamText.BLOCK_CLOSING)
        assertEquals("I will delegate.", TeamText.withoutBlocks(lines("I will delegate.", "", block)))
        assertEquals(lines("Before.", "", "After."), TeamText.withoutBlocks(lines("Before.", block, "", "After.")))
    }

    @Test
    fun `an answer that is only a block shows nothing`() {
        val block = lines(TeamText.BLOCK_OPENING, """{"delegate": []}""", TeamText.BLOCK_CLOSING)
        assertEquals("", TeamText.withoutBlocks(block))
        assertEquals("", TeamText.withoutBlocks(lines("", "  $block", "")))
    }

    @Test
    fun `every block is left out, another fenced block is kept`() {
        val block = lines(TeamText.BLOCK_OPENING, "{}", TeamText.BLOCK_CLOSING)
        val code = lines("```json", "{}", "```")
        assertEquals(lines("One.", code, "Two."), TeamText.withoutBlocks(lines("One.", block, code, block, "Two.")))
    }

    @Test
    fun `a block still streaming never shows a piece of itself`() {
        val whole = lines("I will delegate.", "", TeamText.BLOCK_OPENING, """{"delegate": [{"member": "A"}]}""", TeamText.BLOCK_CLOSING)
        val start = whole.indexOf(TeamText.BLOCK_OPENING)
        // From the fourth character of the opening line on, nothing of the block shows.
        for (end in start + TeamText.BLOCK_CLOSING.length + 1..whole.length) {
            assertEquals("I will delegate.", TeamText.withoutBlocks(whole.take(end)), "After ${whole.take(end)}")
        }
    }

    @Test
    fun `a line that only starts like a fence of another language is kept`() {
        val answer = lines("Look:", "```text", "plain", "```")
        assertEquals(answer, TeamText.withoutBlocks(answer))
    }

    @Test
    fun `the conversation of a member is named after the team's and the member`() {
        assertEquals("K#id-Backend", TeamText.memberConversation("K", "id-Backend"))
    }
}
