package org.example.kotlineditor

import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentPlanEntry
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.markdownOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user copies of a conversation: its messages, one block each, Markdown. */
class AgentTranscriptTextTest {
    @Test
    fun `a prompt copied names the user and carries what was joined to it`() {
        val copied = markdownOf(listOf(AgentItem.UserText("u1", 1, "echo one", listOf("NOTES.md", "Main.kt:3-7"))))
        assertEquals("## You\necho one\n\n*(NOTES.md, Main.kt:3-7)*", copied.trim())
    }

    @Test
    fun `an answer is copied as it was said`() {
        val copied = markdownOf(listOf(AgentItem.AgentText("a1", 1, "one chunk")))
        assertEquals("one chunk", copied.trim())
    }

    @Test
    fun `a thought and a notice come out quoted`() {
        val copied =
            markdownOf(
                listOf(
                    AgentItem.Thought("t1", 1, "Is it there?\nMaybe."),
                    AgentItem.Notice("n1", 1, "Stopped.", false),
                ),
            )
        assertEquals("> Is it there?\n> Maybe.\n\n> Stopped.", copied.trim())
    }

    @Test
    fun `an action of the agent is one item, its output indented`() {
        val copied =
            markdownOf(listOf(AgentItem.ToolCall("t1", 1, "Read README.md", AgentToolKind.Read, AgentToolStatus.Completed, "one line")))
        assertEquals("- **Action**: Read README.md (completed)\n    one line", copied.trim())
    }

    @Test
    fun `a plan copied is one checkbox by step`() {
        val plan =
            AgentItem.Plan(
                "p1",
                1,
                listOf(AgentPlanEntry("open the file", "completed"), AgentPlanEntry("write it", "in_progress")),
            )
        val copied = markdownOf(listOf(plan))
        assertEquals("## Plan\n- [x] open the file\n- [ ] write it", copied.trim())
    }

    @Test
    fun `a request for permission says what the user chose`() {
        val answered = AgentItem.Permission("p1", 1, "Edit of NOTES.md", emptyList(), chosen = "allow")
        val waiting = AgentItem.Permission("p2", 1, "Boom", emptyList())
        val copied = markdownOf(listOf(answered, waiting)).trim()
        assertEquals(
            "- **Permission**: Edit of NOTES.md: allow\n\n- **Permission**: Boom",
            copied,
        )
    }
}
