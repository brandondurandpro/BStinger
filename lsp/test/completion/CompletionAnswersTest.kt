package org.example.kotlineditor.lsp.completion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.example.kotlineditor.model.CompletionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionAnswersTest {
    private fun item(label: String, kind: Int = 3, extra: String = "") = """{"label":"$label","kind":$kind$extra}"""

    private fun edit(text: String, start: Int, end: Int) =
        ""","textEdit":{"range":{"start":{"line":0,"character":$start},"end":{"line":0,"character":$end}},""" +
            """"newText":"$text"}"""

    private fun list(vararg items: String, incomplete: Boolean = false) =
        Json.parseToJsonElement("""{"isIncomplete":$incomplete,"items":[${items.joinToString(",")}]}""")

    @Test
    fun `items are filtered by the word before the caret, prefixes first`() {
        val answer =
            list(
                item("str_replace"),
                item("strlen"),
                item("array_map"),
                item("substr"),
                item("STRTOUPPER"),
                item("sprintf"),
                item("ustr"),
            )
        val result = CompletionAnswers.translate(answer, "echo str", 8)
        assertEquals(5, result.replaceFrom)
        // "substr" has s, t, r in order; "sprintf" has not, and "ustr" does not start with s.
        assertEquals(listOf("str_replace", "strlen", "STRTOUPPER", "substr"), result.items.map { it.label })
        assertTrue(result.ready)
        assertFalse(result.incomplete)
    }

    @Test
    fun `a variable keeps its dollar, from the server's edit`() {
        val line = "echo \$us"
        val answer = list(item("\$user", 6, edit("\$user", 5, 8)), item("\$other", 6, edit("\$other", 5, 8)))
        val result = CompletionAnswers.translate(answer, line, 8)
        assertEquals(5, result.replaceFrom)
        assertEquals(listOf("\$user"), result.items.map { it.label })
        assertEquals(CompletionKind.Variable, result.items.single().kind)
        assertEquals("\$user", result.items.single().insertText)
    }

    @Test
    fun `items whose edit starts further right get the text in between`() {
        // The server replaces "us" only, after the dollar: the client replaces from the dollar.
        val answer = list(item("user", 6, edit("user", 6, 8)), item("\$used", 6, edit("\$used", 5, 8)))
        val result = CompletionAnswers.translate(answer, "echo \$us", 8)
        assertEquals(5, result.replaceFrom)
        assertEquals(setOf("\$user", "\$used"), result.items.map { it.insertText }.toSet())
    }

    @Test
    fun `nothing typed lists everything in the server's order`() {
        val answer =
            list(item("b", extra = ""","sortText":"2""""), item("a", extra = ""","sortText":"3""""), item("c"))
        val result = CompletionAnswers.translate(answer, "\$x->", 4)
        assertEquals(4, result.replaceFrom)
        assertEquals(listOf("b", "a", "c"), result.items.map { it.label })
    }

    @Test
    fun `snippets are inserted as plain text`() {
        val answer =
            list(
                item("strlen", extra = ""","insertText":"strlen(${'$'}{1:string})${'$'}0","insertTextFormat":2"""),
            )
        val result = CompletionAnswers.translate(answer, "strl", 4)
        assertEquals("strlen(string)", result.items.single().insertText)
    }

    @Test
    fun `answers are capped and then incomplete`() {
        val answer = list(*Array(500) { item("fn_%03d".format(it)) })
        val result = CompletionAnswers.translate(answer, "fn_", 3)
        assertEquals(MAX_COMPLETION_ITEMS, result.items.size)
        assertEquals("fn_000", result.items.firstOrNull()?.label)
        assertTrue(result.incomplete)
    }

    @Test
    fun `null and bare arrays are answers too`() {
        assertTrue(CompletionAnswers.translate(JsonNull, "x", 1).items.isEmpty())
        val array = Json.parseToJsonElement("[${item("xyz")}]")
        assertEquals(listOf("xyz"), CompletionAnswers.translate(array, "x", 1).items.map { it.label })
    }
}
